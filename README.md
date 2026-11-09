# Tally — P2P Wallet & Payments API

A Venmo-style wallet: open a wallet, add money, send money to another user, refund a payment, and browse your activity. The backend is **Java 21 + PostgreSQL** with a hand-built REST layer; the frontend is **React**.

The interesting part is the money path. Every transfer is one database transaction that is safe under retries, concurrency, and partial failure:

| Problem | How Tally handles it |
|---|---|
| Client retries after a timeout | Every write takes an `Idempotency-Key`. A repeat returns the original result (`200`, `Idempotent-Replayed: true`) instead of charging again. Reusing a key with a different body is rejected (`422 idempotency_key_reused`). |
| 50 identical requests at the same instant | The key is a `UNIQUE` column; losers of the race read back the winner's transfer. Tested: exactly one `201`. |
| Two users paying each other at once | Both wallet rows are locked with `SELECT … FOR UPDATE` **in UUID order**, so opposite transfers can't deadlock. Tested with 400 concurrent A↔B transfers. |
| Overdraft | Funds are checked under the row lock; a `CHECK (balance >= 0)` constraint backs it up in the database. |
| Silent balance drift | Double-entry ledger: each movement writes two entries that sum to zero, each with `balance_after`. `GET /api/admin/reconciliation` proves total money = 0 and every balance = sum of its entries. |
| Floating-point money | Amounts are `BIGINT` paise end-to-end; the JSON parser keeps integers as `long`. |

## Results

Measured with `bench/LoadTest.java` on a **2 vCPU / 8 GB VM** with the app, PostgreSQL 16 and the load generator all on the same machine (so these are conservative):

```
1,000 wallets · 32 concurrent clients · 20 s · random P2P transfers
requests=10,736  errors=0  throughput≈537 transfers/s
latency: p50=55 ms  p95=89 ms  p99=124 ms
reconciliation: totalBalance=0, driftedAccounts=0, unbalancedMovements=0  → ok
```

Test suite: **16 tests** (JSON parser, API behaviour, concurrency) — including 2,000 random transfers across 8 hot wallets from 64 threads with money conserved and no negative balances.

## API

All bodies are JSON; amounts are integers in paise. Writes require `Idempotency-Key`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/accounts` | `{ownerName, email, currency?}` → `201`; duplicate email → `409` |
| `GET` | `/api/accounts` / `/api/accounts/{id}` | |
| `POST` | `/api/accounts/{id}/deposits` | `{amount}` — add money (from the system funding account) |
| `POST` | `/api/transfers` | `{fromAccountId, toAccountId, amount, note?}` → `201`; `422 insufficient_funds` |
| `GET` | `/api/transfers/{id}` | status `COMPLETED` / `PARTIALLY_REFUNDED` / `REFUNDED` |
| `POST` | `/api/transfers/{id}/refunds` | `{amount?}` (omit for the remainder); partial refunds supported |
| `GET` | `/api/accounts/{id}/ledger?limit=&before=` | newest-first, keyset pagination via `nextCursor` |
| `GET` | `/api/admin/reconciliation` | ledger invariants |

Errors are always `{"error": {"code": "...", "message": "..."}}` with a matching HTTP status (`400`, `404`, `405`, `409`, `413`, `422`).

```bash
curl -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 7c0e…' \
  -d '{"fromAccountId":"…","toAccountId":"…","amount":24950,"note":"Coffee"}'
```

## Architecture

```
React (Vite) ──HTTP/JSON──► JDK HttpServer (virtual thread per request)
                               │  Router: path params, CORS, body limits, JSON errors, SPA hosting
                               ▼
                           WalletService  ── idempotency → lock (ordered) → validate → write
                               │
                           Tx (retry on 40001/40P01) ─► ConnectionPool (fixed, self-healing)
                               ▼
                           PostgreSQL: accounts · transfers · refunds · ledger_entries
                           (migrations in db/migrations, applied at boot under an advisory lock)
```

No framework on purpose: routing, JSON, pooling and migrations are each a small, readable class, so the request path from socket to SQL fits on a few screens. The only runtime dependency is the PostgreSQL JDBC driver.

```
src/main/java/dev/nishant/tally/
  App.java                 boot: pool → migrate → routes → server
  api/Routes.java          REST endpoints
  http/Router.java         routing, errors, static hosting
  http/Json.java           strict JSON reader/writer
  service/WalletService.java   transfers, refunds, ledger, reconciliation
  db/ConnectionPool.java · db/Tx.java · db/Migrator.java
db/migrations/V1__init.sql     schema + constraints + indexes
src/test/java/…               JsonTest · ApiTest · ConcurrencyTest
bench/LoadTest.java           closed-loop load generator
web/                          React UI (send money, activity feed, refunds)
```

## Run it

Requires JDK 21+, PostgreSQL 14+, Node 20+.

```bash
createdb tally                       # or: psql -c 'create database tally'
./scripts/build.sh                   # compiles, fetches the JDBC driver once
(cd web && npm install && npm run build)
java -jar build/tally.jar            # http://localhost:8080 (API + UI)

# tests (creates and drops a tally_test database)
./scripts/test.sh

# load test against a running server
java -cp build/test-classes LoadTest http://localhost:8080 1000 32 20
```

Config via env: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` (20), `PORT` (8080).

For frontend development, `cd web && npm run dev` proxies `/api` to the Java server.
