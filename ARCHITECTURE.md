# 💰 Java Wallet — Architecture & Usage

A double-entry bookkeeping service that tracks account balances and money
movements over a REST API — engineered so that **an incorrect balance can never
be observed**, even under heavy concurrency or across a cluster of nodes.

`Java 17` · `Spring Boot 3.2.5` · `Spring Web` · `Spring Data JPA` ·
`Bean Validation` · `Spring Security · OAuth2 JWT` · `SLF4J logging` ·
`springdoc / Swagger UI` · `H2 / PostgreSQL-ready` · `JUnit 5 · AssertJ` · `Maven`

| Priority | Pillar | What it means |
|----------|--------|---------------|
| **1** | **Correctness** | Money is conserved and balances always reconcile to the ledger — enforced in the database, not in app memory. |
| **2** | **Readability** | Thin controllers, one service of bookkeeping logic, immutable DTO `record`s, no Lombok magic. |
| **3** | **Cluster-safety** | Pessimistic row locks + ordered acquisition + a unique idempotency key — correct on N nodes with zero code changes. |

---

## Contents

- [Overview](#overview)
- [Layered architecture](#layered-architecture)
- [Data model](#data-model)
- [Transfer flow](#transfer-flow-the-heart-of-the-system)
- [Concurrency & correctness](#concurrency--correctness)
- [Security (OAuth2)](#security--oauth-20)
- [Logging & observability](#logging--observability)
- [API reference](#api-reference)
- [How to run & use](#how-to-run--use)

---

## Overview

The application exposes two REST resources — `/accounts` and `/transfers` —
backed by a single transactional service. Internally it is an **append-only
double-entry ledger**: every movement of money writes immutable signed entries,
and each account's running `balance` is a cached projection of those entries
that is always kept in lock-step inside the same database transaction.

**What it does**
- Create single-currency accounts
- Deposit, withdraw, and transfer funds
- Read balances and an auditable per-account ledger
- Safely retry requests via an idempotency key
- Authenticate callers with OAuth 2.0 JWT bearer tokens

**What makes it interesting**
- Money is exact `BigDecimal` — never floating point
- Operation type is *inferred* from which account ids are present
- Concurrency control lives in the DB, so it scales horizontally
- A stress test asserts conservation + ledger reconciliation

> **The one-sentence pitch:** a wallet where correctness is a database-enforced
> invariant rather than something the application code hopes to maintain.

---

## Layered architecture

A conventional, deliberately boring Spring layering. Each layer has one job;
dependencies point strictly downward. The path below shows the request flow for
a transfer.

```
Client          HTTP client (curl · Postman · service)
   │  JSON over HTTP · Authorization: Bearer <jwt>
   ▼
Security        SecurityFilterChain (OAuth2 resource server · validates JWT · scope→route rules)
                TokenController (/oauth/token · mints JWTs)
   │  authenticated request (SCOPE_wallet.read / SCOPE_wallet.write)
   ▼
Web             AccountController (/accounts) · TransferController (/transfers)
                GlobalExceptionHandler (@RestControllerAdvice)
   │  validated DTO records (TransferRequest, CreateAccountRequest)
   ▼
Service         WalletService (@Transactional · all bookkeeping rules · locking · idempotency)
   │  Spring Data repositories
   ▼
Data            AccountRepository (findByIdForUpdate) · TransactionRepository (findByIdempotencyKey)
                LedgerEntryRepository (findByAccountId…)
   │  JPA / Hibernate · SELECT … FOR UPDATE
   ▼
DB              accounts (balance cached) · transactions (headers + uk_idempotency)
                ledger_entries (append-only, signed)
```

| Layer | Responsibility | Key types |
|-------|----------------|-----------|
| **Security** | Stateless OAuth2 resource server: validates the JWT bearer token and enforces scope-per-route. `TokenController` issues tokens via the client-credentials grant. | `SecurityConfig`, `TokenController`, `OAuthClientProperties` |
| **Web / Controller** | HTTP mapping, request validation (`@Valid`), exception→status translation. No business logic. | `AccountController`, `TransferController`, `GlobalExceptionHandler` |
| **DTO** | Immutable request/response contracts as Java `record`s; isolate the wire format from entities. | `TransferRequest`, `AccountResponse`, `BalanceResponse`, `LedgerEntryResponse`, `ErrorResponse` |
| **Service** | The transaction boundary. All correctness rules: validation, type resolution, locking order, debit/credit, ledger writes, idempotency. | `WalletService` |
| **Domain / Model** | JPA entities that own their invariants — e.g. `Account.debit()` refuses to go negative. | `Account`, `Transaction`, `LedgerEntry`, `TransactionType` |
| **Repository** | Spring Data JPA persistence, including the pessimistic-lock query. | `AccountRepository` (+ `findByIdForUpdate`), `TransactionRepository`, `LedgerEntryRepository` |

---

## Data model

Three tables implementing double-entry bookkeeping. The ledger is the source of
truth; the account balance is a cached aggregate that must always equal the sum
of its entries.

**`accounts`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `currency` | char(3) | |
| `balance` | DECIMAL(19,4) | ← cached |
| `created_at` | timestamp | |

**`transactions`** (header)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `idempotency_key` | | UNIQUE |
| `type` | enum | DEPOSIT \| WITHDRAWAL \| TRANSFER |
| `from_account_id`, `to_account_id` | UUID | |
| `amount` | DECIMAL(19,4) | |
| `currency`, `timestamp` | | |

**`ledger_entries`** (append-only)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `account_id` | UUID | indexed |
| `transaction_id` | UUID | |
| `amount` | DECIMAL(19,4) | ← signed |
| `balance_after` | DECIMAL(19,4) | |
| `timestamp` | | |

One **transaction** → one or two **ledger entries**. A `TRANSFER` writes a debit
`−X` on the sender and a credit `+X` on the receiver; a `DEPOSIT` / `WITHDRAWAL`
writes a single entry.

**Why a signed, append-only ledger?** Entries are never updated or deleted, so
the history is auditable and tamper-evident. Each row also stores
`balance_after`, giving a running balance and making the core invariant trivially
checkable: `balance == Σ(entry.amount)`.

**Why cache the balance at all?** Reading a balance is the hot path. Summing a
growing ledger on every read is O(history). The cached column makes reads O(1)
while the ledger stays authoritative — and the two are written together under one
lock, so they can never drift.

---

## Transfer flow (the heart of the system)

A single `POST /transfers` endpoint handles deposit, withdrawal, and transfer.
The operation is inferred from which account ids are present — both → transfer,
only *to* → deposit, only *from* → withdrawal.

1. **Idempotency check.** If an `idempotencyKey` is supplied and already exists,
   the stored transaction is returned (after verifying the parameters match) — no
   money moves twice. Mismatched params on a reused key → `409`.
2. **Validate & classify.** Amount > 0, currency present, at least one account id,
   sender ≠ receiver. Then `resolveType()` maps the id combination to a
   `TransactionType`.
3. **Acquire locks in deterministic order.** Affected accounts are loaded with
   `SELECT … FOR UPDATE`, sorted by ascending UUID. This global ordering is what
   makes deadlock between opposing transfers impossible.
4. **Enforce business rules.** Currencies must match the transaction currency;
   `Account.debit()` throws `InsufficientFundsException` rather than letting a
   balance go negative.
5. **Apply & record atomically.** Debit/credit the in-memory entities, save the
   transaction header, then append the signed ledger entry(ies) — all inside one
   `@Transactional` boundary.
6. **Commit.** JPA flushes the managed entities; balance change + ledger entries
   commit or roll back *together*. Locks release on commit.

### The locking + atomicity core, in code

```java
@Transactional
public Transaction transfer(TransferRequest request) {
    // 1. Idempotency — return the prior result instead of re-applying
    if (key != null) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) return verifyIdempotentMatch(existing.get(), request);
    }

    validate(request);
    TransactionType type = resolveType(request);   // TRANSFER / DEPOSIT / WITHDRAWAL

    switch (type) {
      case TRANSFER -> {
        // 3. Lock BOTH rows in ascending-id order → no deadlock
        Map<UUID,Account> locked = lockAccounts(List.of(fromId, toId));
        Account from = locked.get(fromId), to = locked.get(toId);

        requireSameCurrency(from, currency);
        requireSameCurrency(to, currency);

        from.debit(amount);    // throws if it would go negative
        to.credit(amount);

        transactionRepository.save(transaction);
        recordEntry(from, transaction, amount.negate());  // −X
        recordEntry(to,   transaction, amount);           // +X
      }
      // DEPOSIT / WITHDRAWAL: lock one account, single ledger entry
    }
    return transaction;   // managed entities flush on commit
}
```

```java
// Always acquire locks in a consistent global order (ascending UUID)
private Map<UUID,Account> lockAccounts(List<UUID> ids) {
    Map<UUID,Account> locked = new LinkedHashMap<>();
    ids.stream().distinct().sorted().forEach(id -> {
        Account account = accountRepository.findByIdForUpdate(id)   // SELECT … FOR UPDATE
                .orElseThrow(() -> new AccountNotFoundException(id));
        locked.put(id, account);
    });
    return locked;
}
```

---

## Concurrency & correctness

The brief's headline rule — "it should never be possible to get an incorrect
balance" — is reduced to two invariants and enforced entirely at the database
layer.

- **Invariant 1 — Conservation.** The sum of all balances never changes; no money
  is created or destroyed. A lost update (the classic missing-lock bug) would
  break this.
- **Invariant 2 — Ledger consistency.** For every account,
  `balance == Σ(ledger entry amounts)`. The cached balance can never silently
  drift from the source of truth.

| Mechanism | What it guarantees |
|-----------|--------------------|
| **One transaction per mutation** (`@Transactional`) | Balance change and ledger entries commit or roll back together. A rejected transfer (e.g. insufficient funds) leaves no trace. |
| **Pessimistic row locks** (`SELECT … FOR UPDATE`) | Held until commit, so two transfers touching the same account are serialised — lost updates are impossible. |
| **Ordered lock acquisition** (ascending UUID) | A consistent global lock order prevents deadlock between transfers moving money the opposite way between the same pair. |
| **Idempotency key** (UNIQUE constraint) | Safe client retries. Even two concurrent same-key requests can create at most one transaction (the DB rejects the second). |
| **Entity-level guard** (`Account.debit()`) | A balance can never go negative — the rule lives on the entity, not just in the service. |

> **Why this clusters with zero code changes.** Nothing relies on JVM-local state
> — no `synchronized`, no in-memory map. The locks and the unique constraint are
> enforced by the database for *all* connections. Run N identical wallet nodes
> against one shared PostgreSQL and it stays correct; the only reason the demo
> runs single-node is that H2 is in-memory and not shared. Swapping in PostgreSQL
> is a `spring.datasource.url` change.

**The stress test that proves it.** `WalletServiceConcurrencyTest` seeds 8
accounts with 1,000.00 EUR each, then fires **8 threads × 200 = 1,600 overlapping
random transfers** released simultaneously via a `CountDownLatch`. After the storm
it asserts both invariants: total money unchanged, and every balance still equals
the sum of its ledger entries. If locking were missing or the order inconsistent,
conservation would fail or it would deadlock.

---

## Security — OAuth 2.0

Every `/accounts` and `/transfers` call must carry a JWT bearer token. The wallet
is a stateless **OAuth 2.0 Resource Server**: it validates the token's signature
and expiry, then authorises the call against the scopes inside it. There is no
session and no server-side login state — exactly what lets it run on N nodes.

**Scopes → endpoints**
- `wallet.write` — required for `POST /accounts` and `POST /transfers`
- `wallet.read` — required for every `GET /accounts/**`

The token's `scope` claim is mapped to Spring `SCOPE_*` authorities and checked
per route in `SecurityConfig`.

**Open (unauthenticated) paths**
- `POST /oauth/token` — get a token
- `/swagger-ui/**`, `/v3/api-docs/**` — docs
- `/h2-console/**` — dev DB console

**How a request is authorised**

```
client ──(client_id + client_secret)──▶ POST /oauth/token
                                          │  client-credentials grant
       ◀───────── signed JWT ────────────┘  (scope: "wallet.read wallet.write")

client ──(Authorization: Bearer <jwt>)──▶ POST /transfers
                                          │  1. JwtDecoder verifies RS256 signature + expiry
                                          │  2. scope claim → SCOPE_wallet.write authority
                                          │  3. route rule requires SCOPE_wallet.write  ✓
       ◀──────── 201 Created ────────────┘
```

> **Self-contained by design.** To keep the project zero-setup, an RSA key pair is
> generated *in memory at startup* and a single static demo client (`demo-client`
> / `demo-secret`) is configured in `application.properties`. The same service both
> *mints* tokens (`/oauth/token`, the `JwtEncoder`) and *verifies* them (the
> `JwtDecoder`). Tokens are signed with RS256 and do not survive a restart (the
> key is regenerated).

> **Production swap.** Delete `TokenController` and point the resource server at a
> real Authorization Server (Keycloak / Auth0 / Spring Authorization Server) with a
> single property — `spring.security.oauth2.resourceserver.jwt.issuer-uri`. The
> route-level scope rules stay exactly as they are.

---

## Logging & observability

Structured SLF4J logging runs through the whole request path so each money
movement, auth decision and rejection leaves an auditable trail. Business events
log at `INFO`; per-step mechanics at `DEBUG`; client mistakes at `WARN`; only
genuine surprises at `ERROR` (with a stack trace).

| Where | Level | What it records |
|-------|-------|-----------------|
| `WalletService` | INFO | account created; money moved (`type, tx, from, to, amount`); idempotent replays returning the original transaction |
| `WalletService` | DEBUG | request shape before processing; the exact set + order of accounts write-locked |
| `TokenController` | INFO / WARN | token issued (client + granted scopes); rejected on bad grant, client or scope — never logs the secret |
| `GlobalExceptionHandler` | WARN / ERROR | 4xx client errors at WARN/DEBUG so a noisy caller can't flood ERROR; unexpected 5xx at ERROR with stack trace |
| `SecurityConfig` / startup | INFO | security wiring, generated signing-key id, and the token / Swagger URLs printed once on boot |

Levels are tuned in `application.properties`: `com.wallet=DEBUG`,
`root=INFO`, with a concise timestamped console pattern. A sample money-movement
line:

```
# INFO  c.c.wallet.service.WalletService - TRANSFER applied: tx=f3e5c75a…, from=cc2a72ac…, to=55ba3daa…, amount=30.00 EUR
```

---

## API reference

Base URL `http://localhost:8080`. All bodies are JSON. Money is serialised at
4-decimal scale; timestamps are ISO-8601.

> **Auth required.** Every `/accounts` and `/transfers` endpoint needs an
> `Authorization: Bearer <jwt>` header (scope `wallet.write` for POSTs,
> `wallet.read` for GETs). Get a token from `POST /oauth/token` below. A
> missing/expired token returns `401`; a valid token lacking the required scope
> returns `403`.

### `POST /oauth/token` — obtain an access token

Client-credentials grant. Send `application/x-www-form-urlencoded`; the response
is a standard OAuth2 token body.

```bash
curl -s -X POST http://localhost:8080/oauth/token \
  -d grant_type=client_credentials \
  -d client_id=demo-client -d client_secret=demo-secret

# 200 OK
{"access_token":"eyJraWQ…","token_type":"Bearer","expires_in":3600,"scope":"wallet.read wallet.write"}
```

### `POST /accounts` — create an account

```jsonc
// request
{ "currency": "EUR" }

// 201 Created
{
  "id": "1f0a…",
  "currency": "EUR",
  "balance": 0.0000,
  "createdAt": "2026-…"
}
```

### `GET /accounts/{id}` · `GET /accounts/{id}/balance`

```jsonc
// GET /accounts/{id}/balance → 200 OK
{ "accountId": "1f0a…", "currency": "EUR", "balance": 100.0000 }
```

### `POST /transfers` — deposit / withdraw / transfer

The presence of `fromAccountId` / `toAccountId` selects the operation:

| from | to | meaning |
|------|----|---------|
| ✓ | ✓ | transfer between two accounts |
| — | ✓ | deposit (funds enter from outside) |
| ✓ | — | withdrawal (funds leave the system) |

```jsonc
// request
{
  "idempotencyKey": "opt-unique",
  "fromAccountId": "…",  // omit → deposit
  "toAccountId":   "…",  // omit → withdrawal
  "amount": 25.00,
  "currency": "EUR"
}

// 201 Created
{
  "transactionId": "…",
  "type": "TRANSFER",
  "fromAccountId": "…",
  "toAccountId": "…",
  "amount": 25.0000,
  "currency": "EUR",
  "timestamp": "…"
}
```

### `GET /accounts/{id}/transactions` — the account ledger

`200 OK` — list of ledger entries oldest-first, each with the signed `amount` and
the `balanceAfter` at that point.

### Error contract

Every error returns the same shape via `GlobalExceptionHandler`:

```json
{ "status": 422, "error": "Unprocessable Entity", "message": "Insufficient funds …", "timestamp": "…" }
```

| Situation | Status | Exception |
|-----------|--------|-----------|
| Account not found | 404 | `AccountNotFoundException` |
| Malformed / invalid request | 400 | `InvalidTransferException` · bean validation |
| Insufficient funds | 422 | `InsufficientFundsException` |
| Currency mismatch | 422 | `CurrencyMismatchException` |
| Idempotency key reused with different params / race | 409 | `IdempotencyConflictException` · `DataIntegrityViolation` |
| Missing / expired / invalid bearer token | 401 | Spring Security (resource server) |
| Valid token without the required scope | 403 | Spring Security (scope rule) |
| Bad grant / client / scope at `/oauth/token` | 400 / 401 | `OAuthTokenException` |

---

## How to run & use

Requires JDK 17+ and Maven. Zero external setup — the demo runs on an in-memory
H2 database.

### 1 · Build & run

```bash
# run the server (http://localhost:8080)
mvn spring-boot:run

# run the full test suite incl. the concurrency stress test
mvn test

# build and run a self-contained jar
mvn clean package
java -jar target/java-wallet-1.0.0.jar
```

> The H2 web console is at <http://localhost:8080/h2-console> — JDBC URL
> `jdbc:h2:mem:wallet`, user `sa`, no password — if you want to peek at the tables.

### 2 · Explore the API in the browser (Swagger UI)

Docs are generated automatically from the controllers and DTOs by
[springdoc-openapi](https://springdoc.org/) — every endpoint is callable straight
from the page.

- **Swagger UI:** <http://localhost:8080/swagger-ui.html> — interactive "Try it out"
- **OpenAPI 3 spec (JSON):** <http://localhost:8080/v3/api-docs> — import into Postman / generate clients

> Endpoints are OAuth2-protected, so first call `POST /oauth/token` from the page
> (or `demo.sh`), copy the `access_token`, click **Authorize 🔒** at the top of
> Swagger UI and paste it — then "Try it out" sends the bearer header on every
> request.

### 3 · End-to-end walkthrough (curl)

```bash
BASE=http://localhost:8080

# 0. get an OAuth2 token and reuse it as a bearer header
TOKEN=$(curl -s -X POST $BASE/oauth/token \
      -d grant_type=client_credentials \
      -d client_id=demo-client -d client_secret=demo-secret \
      | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
AUTH="Authorization: Bearer $TOKEN"

# create two EUR accounts (capture the ids)
A=$(curl -s -X POST $BASE/accounts -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"currency":"EUR"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
B=$(curl -s -X POST $BASE/accounts -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"currency":"EUR"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')

# deposit 100 into A  (only toAccountId → DEPOSIT)
curl -s -X POST $BASE/transfers -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"toAccountId\":\"$A\",\"amount\":100.00,\"currency\":\"EUR\"}"

# transfer 30 from A to B  (both ids → TRANSFER)
curl -s -X POST $BASE/transfers -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":30.00,\"currency\":\"EUR\"}"

# balances: A = 70, B = 30
curl -s $BASE/accounts/$A/balance -H "$AUTH"
curl -s $BASE/accounts/$B/balance -H "$AUTH"

# A's ledger (oldest first, with running balanceAfter)
curl -s $BASE/accounts/$A/transactions -H "$AUTH"
```

> **`demo.sh` is ready to run this curl end-to-end test.** A ready-to-run version
> of the whole walkthrough above lives in `demo.sh` at the project root — start the
> server, then execute `bash demo.sh` (override the host with `BASE=…`) to drive the
> full flow and assert the expected balances and ledger in one go.

### 4 · Run the test collection (Postman / newman)

`wallet.postman_collection.json` drives the same end-to-end flow as `demo.sh` but
adds an assertion to each step. It is **self-contained** — the access token, both
account ids, and a fresh per-run idempotency key are captured into collection
variables by test scripts, so there is nothing to fill in by hand.

```bash
# run headless from the CLI with newman (no install needed if you have npx)
npx --yes newman run wallet.postman_collection.json

# override the target without editing the file
npx --yes newman run wallet.postman_collection.json \
  --env-var baseUrl=http://localhost:8080 \
  --env-var clientId=demo-client \
  --env-var clientSecret=demo-secret
```

- **CLI (newman):** a green run reports **9 requests / 6 assertions, 0 failures**.
- **Postman app:** *Import* → `wallet.postman_collection.json` → open **Java Wallet API** → **Run**. Requests are ordered (token → accounts → transfers → balances), so use the Collection Runner rather than firing them out of order.

> Because the idempotency key is regenerated per run, the collection is safely
> **re-runnable** against a long-lived server — no `409` conflict on the second pass.

### 5 · Try the safety nets

- **Idempotent retry** — send the same `POST /transfers` twice with the same
  `idempotencyKey`; the money moves once and both calls return the same transaction.
- **Insufficient funds** — withdraw more than a balance → `422`, and nothing is
  written (verify the ledger is unchanged).
- **Currency mismatch** — transfer `USD` between `EUR` accounts → `422`.
- **Auth required** — call any endpoint without the bearer header → `401`; request
  a `wallet.read`-only token (`-d scope=wallet.read`) and try a `POST` → `403`.
- **Concurrency proof** — run `mvn test` and watch `WalletServiceConcurrencyTest`
  hammer the service with 1,600 overlapping transfers, then assert money is conserved.

### 6 · Point it at PostgreSQL (production / cluster)

```properties
# application.properties — no code changes needed
spring.datasource.url=jdbc:postgresql://db-host:5432/wallet
spring.jpa.hibernate.ddl-auto=validate   # + Flyway/Liquibase migrations
```

---

*Java Wallet · Spring Boot 3.2 / Java 17 · design
priorities: correctness › readability › cluster-safety.*
