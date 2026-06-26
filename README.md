# Java Wallet

A small bookkeeping (wallet) service that tracks account balances and money
movements over a REST API.

The design priorities, in order, match the brief: **correctness first**
(an incorrect balance must never be observable), then **readability**, then
**concurrency / cluster-safety**.

---

## Tech stack

- Java 17+ (compiled for 17; runs on 17, 21, …)
- Spring Boot 3.2 (Spring Web + Spring Data JPA + Bean Validation)
- **PostgreSQL** as the datastore, with **Flyway** schema migrations
  (Hibernate runs with `ddl-auto=validate`); H2 is used only by the tests
- springdoc-openapi (Swagger UI / OpenAPI 3 docs)
- SLF4J / Logback logging — readable console by default, structured JSON under the `prod` profile, with a per-request correlation id
- JUnit 5 / AssertJ for tests

---

## Prerequisites — install everything from scratch

You need a **JDK (Java 17 or newer)** and **Apache Maven** to build the service,
plus a **PostgreSQL** database to run it. The easiest way to get Postgres is
**Docker**, so install that too (the test suite needs neither Postgres nor Docker
— it uses an in-process database). Maven runs on Java, so install the JDK first.
Pick your OS below; every command can be copy-pasted as-is, and there is a
verification step at the end so you know it worked before building.

> **Docker** (for running against PostgreSQL): install
> [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/macOS)
> or Docker Engine (Linux), and confirm it runs with `docker version`.

### Windows 10 / 11 (verified, step by step)

These are the exact steps used to bring this project up on a machine that had
**neither Java nor Maven** installed.

**1. Install the JDK** (using `winget`, which ships with Windows 11 — run in
PowerShell):

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-source-agreements --accept-package-agreements
```

**2. Install Maven.** Apache Maven is *not* in winget, so download the official
binary zip and unpack it (PowerShell):

```powershell
# download Maven 3.9.9 from the Apache archive (a permanent URL)
$ProgressPreference = 'SilentlyContinue'
$zip = "$env:TEMP\maven.zip"
Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip" -OutFile $zip
New-Item -ItemType Directory -Force -Path "C:\Users\$env:USERNAME\tools" | Out-Null
Expand-Archive $zip -DestinationPath "C:\Users\$env:USERNAME\tools" -Force
Remove-Item $zip
```

**3. Set `JAVA_HOME` and add both tools to your PATH** (persists across new
terminals; no admin rights needed):

```powershell
$javaHome = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Where-Object Name -like "jdk-17*" | Select-Object -First 1).FullName
$mvnBin   = "C:\Users\$env:USERNAME\tools\apache-maven-3.9.9\bin"
[Environment]::SetEnvironmentVariable("JAVA_HOME", $javaHome, "User")
$p = [Environment]::GetEnvironmentVariable("Path","User")
foreach ($d in @("$javaHome\bin", $mvnBin)) { if ($p -notlike "*$d*") { $p = "$d;$p" } }
[Environment]::SetEnvironmentVariable("Path", $p, "User")
```

> **Important:** open a **new** PowerShell window after step 3 — environment
> changes only apply to terminals started afterward.

### macOS

```bash
# Homebrew (https://brew.sh) — installs both tools and wires up PATH for you
brew install temurin@17 maven
```

### Linux (Debian / Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven
```

### Verify the install (all platforms)

Open a **new** terminal and confirm both tools are found and on Java 17+:

```bash
java -version     # should report 17.x (or newer)
mvn -version      # should report Maven 3.9.x and the same Java 17 runtime
```

If both commands print versions, you are ready to build. If `mvn` or `java`
is "not recognized / not found", you opened the terminal before setting PATH —
open a fresh one (Windows), or re-run the install (macOS/Linux).

---

## Build and run

The application runs on PostgreSQL, so a new user needs a database. The quickest
way is a local Postgres in Docker — the steps below take you from a clean
checkout to a running server.

```bash
# run the test suite — needs no database (the tests use an in-process H2)
mvn test
```

**1. Start a local PostgreSQL** (Docker; pick another host port if 5432 is taken):

```bash
docker run -d --name wallet-pg -p 5432:5432 -e POSTGRES_DB=wallet -e POSTGRES_USER=wallet -e POSTGRES_PASSWORD=wallet postgres:16-alpine
```

**2. Run the server** against it. Flyway creates the schema on first boot; the
server starts on `http://localhost:8080`. The `WALLET_DB_*` values below are the
defaults, so if your Postgres matches them you can just run `mvn spring-boot:run`.

```bash
# bash / macOS / Linux
WALLET_DB_URL=jdbc:postgresql://localhost:5432/wallet WALLET_DB_USERNAME=wallet WALLET_DB_PASSWORD=wallet mvn spring-boot:run
```

```powershell
$env:WALLET_DB_URL="jdbc:postgresql://localhost:5432/wallet"; $env:WALLET_DB_USERNAME="wallet"; $env:WALLET_DB_PASSWORD="wallet"; mvn spring-boot:run
```

**Build a runnable jar:**

```bash
mvn clean package
WALLET_DB_URL=jdbc:postgresql://localhost:5432/wallet WALLET_DB_USERNAME=wallet WALLET_DB_PASSWORD=wallet java -jar target/java-wallet-1.0.0.jar
```

See [Running on PostgreSQL / as a cluster](#running-on-postgresql--as-a-cluster)
for running multiple nodes against one shared database.

> The first `mvn` command downloads all dependencies from Maven Central and may
> take a few minutes; subsequent runs use the local cache and are fast. An
> internet connection is required for that first build.

> To inspect the database, connect any PostgreSQL client (e.g. **pgAdmin** or
> `psql`) to `localhost:5432`, database `wallet`, user/password `wallet`.

### Interactive API docs (Swagger)

Once the server is running, explore and call every endpoint from the browser:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI 3 spec (JSON):** `http://localhost:8080/v3/api-docs`

Docs are generated automatically from the controllers and DTOs by
[springdoc-openapi](https://springdoc.org/) — no separate spec file to maintain.

---

## Security & configuration

Every `/accounts` and `/transfers` endpoint is protected: the service is a
stateless **OAuth 2.0 resource server**, so each request must carry a JWT bearer
token. Get one from the built-in token endpoint (client-credentials grant), then
send it as an `Authorization: Bearer <token>` header.

```bash
# 1. obtain a token (default demo credentials)
TOKEN=$(curl -s -X POST http://localhost:8080/oauth/token \
      -d grant_type=client_credentials \
      -d client_id=demo-client -d client_secret=demo-secret \
      | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# 2. use it on a protected call
curl -s http://localhost:8080/accounts/$A/balance -H "Authorization: Bearer $TOKEN"
```

**Scopes** are enforced per route:

| Scope          | Grants                              |
|----------------|-------------------------------------|
| `wallet.write` | `POST /accounts`, `POST /transfers` |
| `wallet.read`  | `GET /accounts/**`                  |

A missing/expired token → `401`; a valid token without the required scope → `403`.
Open paths (no token needed): `POST /oauth/token` and the Swagger/OpenAPI docs.

**Credentials & secrets.** The single demo client and token settings live under
`wallet.security.*` in `application.properties`. The id and secret default to the
demo values so the project runs with zero setup, but each can be overridden by an
environment variable so a **real secret is never committed to source control**:

| Property                        | Env override                    | Default       |
|---------------------------------|---------------------------------|---------------|
| `wallet.security.client-id`     | `WALLET_SECURITY_CLIENT_ID`     | `demo-client` |
| `wallet.security.client-secret` | `WALLET_SECURITY_CLIENT_SECRET` | `demo-secret` |

> The JWT signing key is generated in-memory at startup (RS256) and is **not**
> shared across nodes. For a real multi-node deployment, delete `TokenController`
> and point the resource server at an external Authorization Server via
> `spring.security.oauth2.resourceserver.jwt.issuer-uri`.

**Logging.** Readable console by default; set `SPRING_PROFILES_ACTIVE=prod` for
structured one-JSON-object-per-line output. Either way every request is assigned
an `X-Request-Id` correlation id that appears on each log line and is echoed back
on the response header.

---

## Testing

Four ways to exercise the service, from the quickest sanity check to a full
end-to-end run. Ways 2–4 need the server running first (`mvn spring-boot:run`).

### 1. Automated test suite (no server needed)

```bash
mvn test
```

Runs the JUnit 5 suite, including `WalletServiceConcurrencyTest` — 8 threads ×
200 = **1,600 overlapping random transfers** released simultaneously, then
asserts money is conserved and every balance still equals the sum of its ledger
entries. This is the proof of the correctness/concurrency claims above.

It also includes `WalletClusterConcurrencyTest`, which does the same across **two
nodes sharing one real PostgreSQL** (via Testcontainers) — the cross-node proof.
It needs Docker and is skipped automatically when Docker isn't reachable; on
Windows + Docker Desktop you may need
`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.

### 2. End-to-end script — `demo.sh`

A ready-to-run smoke test that mirrors the whole happy path: fetch an OAuth2
token, create two EUR accounts, deposit, transfer, replay the same transfer
with an idempotency key, then print balances and the ledger.

```bash
bash demo.sh
# point at another host with:  BASE=http://my-host:8080 bash demo.sh
```

Expected result: **Balance A = 70.0000**, **Balance B = 30.0000** (the
idempotent replay does not move money twice).

### 3. Postman collection — `wallet.postman_collection.json`

The collection drives the same flow as `demo.sh` with assertions on each step.
It is **self-contained**: the access token, the two account ids, and a per-run
idempotency key are all captured into collection variables by test scripts, so
there is nothing to fill in by hand.

**Run it from the command line with [newman](https://www.npmjs.com/package/newman)**
(no install needed if you have `npx`):

```bash
npx --yes newman run wallet.postman_collection.json
# override the target without editing the file:
npx --yes newman run wallet.postman_collection.json \
  --env-var baseUrl=http://localhost:8080 \
  --env-var clientId=demo-client \
  --env-var clientSecret=demo-secret
```

A green run reports **9 requests / 6 assertions, 0 failures**.

**Or run it in the Postman desktop app:** *Import* → select
`wallet.postman_collection.json` → open the **Java Wallet API** collection →
**Run**. The requests are ordered (token → accounts → transfers → balances), so
use the Collection Runner (or run them top-to-bottom) rather than firing them
out of order.

### 4. Swagger UI (interactive, in the browser)

Open `http://localhost:8080/swagger-ui.html`, call `POST /oauth/token`, copy the
`access_token`, click **Authorize 🔒** and paste it — then "Try it out" sends the
bearer token on every request.

---

## Data model

Three tables, designed around append-only double-entry bookkeeping:

| Table             | Purpose                                                             |
|-------------------|---------------------------------------------------------------------|
| `accounts`        | One row per account: currency + current `balance` (a cached total). |
| `transactions`    | The "header" for each money movement (deposit/withdrawal/transfer). |
| `ledger_entries`  | The per-account, signed effect of each transaction (append-only).   |

A **transfer** writes two ledger entries (a debit `-X` and a credit `+X`); a
**deposit** or **withdrawal** writes one. Because entries are signed and never
mutated, the cached `accounts.balance` is always reconcilable as the sum of an
account's ledger entries — the concurrency test asserts exactly this.

Money is always `BigDecimal`, never floating point.

---

## API

Base URL: `http://localhost:8080`

> Every endpoint below requires an `Authorization: Bearer <token>` header
> (`wallet.write` scope for `POST`, `wallet.read` for `GET`). See
> [Security & configuration](#security--configuration) for how to get a token.

### 1. Create an account

```
POST /accounts
{ "currency": "EUR" }
```
`201 Created`
```json
{ "id": "1f0a…", "currency": "EUR", "balance": 0.0000, "createdAt": "…" }
```

### 2. Get balance

```
GET /accounts/{id}/balance
```
`200 OK`
```json
{ "accountId": "1f0a…", "currency": "EUR", "balance": 100.0000 }
```

### 3. Transfer funds (to and/or from an account)

```
POST /transfers
{
  "idempotencyKey": "optional-unique-string",
  "fromAccountId": "…",   // omit for a deposit
  "toAccountId":   "…",   // omit for a withdrawal
  "amount": 25.00,
  "currency": "EUR"
}
```

The presence of `fromAccountId` / `toAccountId` selects the operation:

| from | to  | meaning                       |
|------|-----|-------------------------------|
| ✓    | ✓   | transfer between two accounts |
|      | ✓   | deposit                       |
| ✓    |     | withdrawal                    |

`201 Created`
```json
{
  "transactionId": "…", "idempotencyKey": "…", "type": "TRANSFER",
  "fromAccountId": "…", "toAccountId": "…",
  "amount": 25.0000, "currency": "EUR", "timestamp": "…"
}
```

### 4. List transactions for an account

```
GET /accounts/{id}/transactions
```
`200 OK` — list of ledger entries (oldest first), each with the signed `amount`
and the `balanceAfter` at that point.

### Errors

All errors return a consistent body:
```json
{ "status": 422, "error": "Unprocessable Entity", "message": "Insufficient funds …", "timestamp": "…" }
```

| Situation                                   | Status |
|---------------------------------------------|--------|
| Account not found                           | 404    |
| Malformed / invalid request                 | 400    |
| Insufficient funds                          | 422    |
| Currency mismatch                           | 422    |
| Idempotency key reused with different params| 409    |
| Missing / expired / invalid bearer token    | 401    |
| Valid token without the required scope      | 403    |

---

## curl walkthrough

```bash
BASE=http://localhost:8080

# 0. obtain an OAuth2 token and reuse it as a bearer header on every call
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

# deposit 100 into A
curl -s -X POST $BASE/transfers -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"toAccountId\":\"$A\",\"amount\":100.00,\"currency\":\"EUR\"}"

# transfer 30 from A to B
curl -s -X POST $BASE/transfers -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":30.00,\"currency\":\"EUR\"}"

# balances: A=70, B=30
curl -s $BASE/accounts/$A/balance -H "$AUTH"
curl -s $BASE/accounts/$B/balance -H "$AUTH"

# A's ledger
curl -s $BASE/accounts/$A/transactions -H "$AUTH"
```

A ready-to-run version of this is in [`demo.sh`](demo.sh).

---

## Concurrency & correctness

The rule "it should never be possible to get an incorrect balance" is enforced
at the database layer:

1. **Every mutation is one transaction.** The balance change and the ledger
   entries are written inside a single `@Transactional` boundary, so they
   commit or roll back together. A failed transfer (e.g. insufficient funds)
   leaves no trace.

2. **Rows are locked before they are changed.** `WalletService` loads each
   affected account with a pessimistic write lock (`SELECT … FOR UPDATE`, via
   `AccountRepository.findByIdForUpdate`). The lock is held until commit, so two
   transfers touching the same account are serialised and lost updates are
   impossible.

3. **Locks are taken in a fixed order.** When a transfer touches two accounts,
   they are locked in ascending id order. A consistent global lock order is what
   prevents deadlock between two transfers moving money the opposite way between
   the same pair of accounts.

4. **Idempotency.** An optional `idempotencyKey` lets a client retry safely
   after a timeout. It is backed by a unique constraint, so even two concurrent
   requests with the same key can produce at most one transaction.

Two tests assert both invariants — total balance never changes, and each balance
always equals the sum of its ledger entries:

- **`WalletServiceConcurrencyTest`** (in-process H2, `test` profile) — thousands
  of overlapping transfers across many threads in one JVM.
- **`WalletClusterConcurrencyTest`** (real PostgreSQL via Testcontainers) — boots
  **two independent application contexts** (two nodes, two connection pools)
  against **one shared database** and fires overlapping transfers from both at the
  same accounts. This is the direct proof of cross-node correctness. *(Requires
  Docker; skipped when Docker is unavailable.)*

### Running as a cluster of wallet servers

This is the key point of the chosen design: **the concurrency control lives in
the database, not in JVM memory.** Nothing in `WalletService` relies on
process-local locks (no `synchronized`, no in-memory map). Pessimistic row locks
and the unique constraint are enforced by the database for *all* connections, so
running N identical wallet nodes against one shared PostgreSQL is correct with
**no code changes** — every node simply points at the same database (see
[below](#running-on-postgresql--as-a-cluster)).

This was verified directly: two nodes against one shared Postgres, 269
concurrent cross-node transfers, money conserved exactly and zero ledger
mismatches.

### Running on PostgreSQL / as a cluster

The service always runs on PostgreSQL, where
[Flyway](src/main/resources/db/migration/V1__init.sql) owns the schema and
Hibernate runs with `ddl-auto=validate` (it checks the entities against the
migrated schema instead of generating it). To run a cluster, point every node at
the **same** database:

```bash
# 1. a shared Postgres (any instance all nodes can reach)
docker run -d --name wallet-pg -p 5432:5432 \
  -e POSTGRES_DB=wallet -e POSTGRES_USER=wallet -e POSTGRES_PASSWORD=wallet \
  postgres:16-alpine

# 2. start one or more nodes against it (real secrets/URLs come from env vars)
WALLET_DB_URL=jdbc:postgresql://localhost:5432/wallet \
WALLET_DB_USERNAME=wallet WALLET_DB_PASSWORD=wallet \
mvn spring-boot:run

# a second node is just another process with a different SERVER_PORT, same DB:
SERVER_PORT=8081 \
WALLET_DB_URL=jdbc:postgresql://localhost:5432/wallet \
WALLET_DB_USERNAME=wallet WALLET_DB_PASSWORD=wallet \
mvn spring-boot:run
```

Connection settings default to a local Postgres but every value is overridable by
the `WALLET_DB_*` environment variables, so real credentials are injected at
deploy time and never committed.

> **Note on auth in a cluster.** Each node currently mints and verifies JWTs with
> an RSA key generated in its own memory, so a token from node A is not valid on
> node B. For a true multi-node deployment, delegate token issuance to an external
> Authorization Server (`spring.security.oauth2.resourceserver.jwt.issuer-uri`) so
> every node trusts the same keys — see [Security & configuration](#security--configuration).

---

## Scope, shortcuts, and the "proper" solution

Kept deliberately within the ~4-hour scope; each shortcut and its production
counterpart:

- **Pessimistic locking.** Simple and obviously correct, which suits a wallet.
  The alternative is **optimistic locking** (a `@Version` column + retry on
  conflict), which scales better under low contention; it would be a small,
  localized change to `Account` and the transfer method.
- **Single currency per account, no FX.** Cross-currency transfers are rejected.
  A real system would add an exchange-rate service and a currency-conversion
  transaction type.
- **Authentication, but not per-resource authorization.** Calls are gated by
  OAuth2 JWT scopes (`wallet.read` / `wallet.write`), and the demo client secret
  is overridable by env var — but tokens are minted in-process by a single static
  client and any valid token may act on *any* account. A real system would
  delegate to an external Authorization Server and add a per-account ownership
  model so a caller can only touch its own accounts.
- **Deposits/withdrawals are single-sided.** A fully closed double-entry system
  would post the contra-entry to an external/clearing account so the books sum
  to zero system-wide; here that contra side is implicit.
- **`balance` is a cached column.** It is always kept consistent with the ledger
  inside the same locked transaction; the ledger remains the source of truth and
  the balance could be recomputed from it at any time.
