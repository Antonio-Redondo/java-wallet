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
- H2 in-memory database (zero setup for the demo)
- springdoc-openapi (Swagger UI / OpenAPI 3 docs)
- JUnit 5 / AssertJ for tests
- No Lombok (DTOs use Java `record`s instead)

---

## Prerequisites — install everything from scratch

You only need **two** tools: a **JDK (Java 17 or newer)** and **Apache Maven**.
Maven itself runs on Java, so install the JDK first. Pick your OS below; every
command can be copy-pasted as-is, and there is a verification step at the end so
you know it worked before building.

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

With the prerequisites above installed and verified:

```bash
# run the server (starts on http://localhost:8080)
mvn spring-boot:run

# run the test suite (includes the concurrency test)
mvn test

# build a runnable jar
mvn clean package
java -jar target/java-wallet-1.0.0.jar
```

> The first `mvn` command downloads all dependencies from Maven Central and may
> take a few minutes; subsequent runs use the local cache and are fast. An
> internet connection is required for that first build.

> The H2 web console is available at `http://localhost:8080/h2-console`
> (JDBC URL `jdbc:h2:mem:wallet`, user `sa`, no password) if you want to peek
> at the tables.

### Interactive API docs (Swagger)

Once the server is running, explore and call every endpoint from the browser:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI 3 spec (JSON):** `http://localhost:8080/v3/api-docs`

Docs are generated automatically from the controllers and DTOs by
[springdoc-openapi](https://springdoc.org/) — no separate spec file to maintain.

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

---

## curl walkthrough

```bash
BASE=http://localhost:8080

# create two EUR accounts (capture the ids)
A=$(curl -s -X POST $BASE/accounts -H 'Content-Type: application/json' \
      -d '{"currency":"EUR"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
B=$(curl -s -X POST $BASE/accounts -H 'Content-Type: application/json' \
      -d '{"currency":"EUR"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')

# deposit 100 into A
curl -s -X POST $BASE/transfers -H 'Content-Type: application/json' \
  -d "{\"toAccountId\":\"$A\",\"amount\":100.00,\"currency\":\"EUR\"}"

# transfer 30 from A to B
curl -s -X POST $BASE/transfers -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":30.00,\"currency\":\"EUR\"}"

# balances: A=70, B=30
curl -s $BASE/accounts/$A/balance
curl -s $BASE/accounts/$B/balance

# A's ledger
curl -s $BASE/accounts/$A/transactions
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

`WalletServiceConcurrencyTest` runs thousands of overlapping transfers across
several accounts and asserts (a) the total balance never changes and (b) each
balance still equals the sum of its ledger entries.

### Running as a cluster of wallet servers

This is the key point of the chosen design: **the concurrency control lives in
the database, not in JVM memory.** Nothing in `WalletService` relies on
process-local locks (no `synchronized`, no in-memory map). Pessimistic row locks
and the unique constraint are enforced by the database for *all* connections, so
running N identical wallet nodes against one shared transactional database
(PostgreSQL, MySQL, …) is correct with **no code changes** — just point
`spring.datasource.url` at the shared database.

The only reason a single node is used in the demo is the in-memory H2 database,
which is not shared. Swapping in PostgreSQL is a configuration change.

---

## Scope, shortcuts, and the "proper" solution

Kept deliberately within the ~4-hour scope; each shortcut and its production
counterpart:

- **Storage is in-memory H2.** Proper: a shared, durable database (PostgreSQL)
  with schema migrations (Flyway/Liquibase) instead of `ddl-auto`. The code is
  already written against standard JPA/SQL so this is config-only.
- **Pessimistic locking.** Simple and obviously correct, which suits a wallet.
  The alternative is **optimistic locking** (a `@Version` column + retry on
  conflict), which scales better under low contention; it would be a small,
  localized change to `Account` and the transfer method.
- **Single currency per account, no FX.** Cross-currency transfers are rejected.
  A real system would add an exchange-rate service and a currency-conversion
  transaction type.
- **No authentication / authorization.** Out of scope for the exercise.
- **Deposits/withdrawals are single-sided.** A fully closed double-entry system
  would post the contra-entry to an external/clearing account so the books sum
  to zero system-wide; here that contra side is implicit.
- **`balance` is a cached column.** It is always kept consistent with the ledger
  inside the same locked transaction; the ledger remains the source of truth and
  the balance could be recomputed from it at any time.

## Possible next steps

Pagination on the transactions endpoint, OpenAPI/Swagger docs, Testcontainers
running the tests against real PostgreSQL, and structured request logging.
