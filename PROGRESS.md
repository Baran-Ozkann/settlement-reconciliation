# Progress

**Current phase:** 4.1 — Bulk write path, done (Phase 5 not started)
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-10-02

## Phase 4.1 — done

- [x] NFR-PERF-1 revised to 120 s in TDD v1.7 (§4.6) by the owner; the perf test asserts 120 s and
  cites the revision. All 17 runs of this phase, every form, took 94.4-113.7 s
- [x] A2, time-ordered line ids (UUID version 7): measured, **not kept**, not committed (table
  below)
- [x] B. The use case counts the bytes it reads from the spooled part and refuses past
  `recon.ingestion.max-file-size` with 413, nothing recorded; the same property feeds the servlet.
  `ApplicationFileSizeLimitTest` with the servlet at four times the limit; break proof
  `ApplicationFileSizeLimitBreakProofTest`
- [x] C. The upload directory is prepared in a bean of its own: startup stops on the system temp
  directory, a filesystem root or the user's home (as configured and through a link), then deletes
  regular files directly in it named `upload_*.tmp`, logging only counts. `UploadDirectoryTest`
  (the three symbolic link cases run on Linux and are skipped by assumption on this Windows
  machine); break proof `UploadDirectoryBreakProofTest`. The property is bound as text: bound as a
  `Path`, a configured `/` became the classpath root before the guard saw it (fixed in `df08898`)
- [x] D. ArchUnit fixtures under `com.baran.archfixture`; `StatementUploadTest` asserts every
  `@RestController` bean is in `adapters.in.web` (recorded one-off proof against `e01b998`)
- [x] E. `docs/break-proofs.md` Phase 4.1 section; per-commit verification in
  `target/verify-results-3.txt`; report `.phase-reports/phase-4.1-report.md`

### A2: time-ordered line ids, measured through the perf test

The change: a generator of our own (no dependency) making RFC 9562 version 7 ids: 48-bit Unix
milliseconds from the injected `Clock`, version and variant bits, then 74 bits from a
`SecureRandom`. Within one millisecond it used the RFC's method 2, "monotonic random": the 74 bits
grow by a random step of 1 to 2^32, seeded below 2^73, the timestamp moving on a millisecond if they
ran out. Both parsers took their line ids from it. Nine unit tests passed (version and variant bits,
ascending byte order over 100,000 ids in one millisecond and across milliseconds, a clock stepping
back, no duplicate in 1,000,000, the timestamp read back, the carry into the high bits), and the
full suite passed with it (957 tests).

Protocol: `.\mvnw.cmd -q -B test -Pperf "-Dtest=StatementIngestionPerformanceTest"` (`-Xmx512m`),
baseline = `1fd90b3` and v7 = the same commit plus the uncommitted change, each in its own worktree
under target/, one run at a time, interleaved in one sitting (2026-10-02, 15:46-15:58). Machine as
in part A; CPU load 1-2 % at each run's start; no other container running.

| Run (in order) | Form | Total | Receive + hash | Parse with inserts | of which inserts | Parse outside inserts | Commit + response | Peak heap |
|---|---|---|---|---|---|---|---|---|
| 1 | baseline | 99.279 s | 1.466 s | 68.960 s | 63.987 s | 4.973 s | 28.841 s | 126 MB |
| 2 | v7 | 94.441 s | 1.470 s | 65.941 s | 60.480 s | 5.461 s | 27.021 s | 134 MB |
| 3 | baseline | 96.424 s | 1.465 s | 67.475 s | 62.337 s | 5.138 s | 27.476 s | 124 MB |
| 4 | v7 | 99.037 s | 1.443 s | 69.923 s | 62.460 s | 7.463 s | 27.662 s | 125 MB |
| 5 | baseline | 100.384 s | 2.405 s | 70.526 s | 63.237 s | 7.289 s | 27.441 s | 116 MB |
| 6 | v7 | 100.090 s | 2.373 s | 69.107 s | 61.005 s | 8.102 s | 28.598 s | 132 MB |

- **Decision: not kept.** Against the baseline run before it, v7 was 4.8 s faster (run 2), 2.6 s
  slower (run 4) and 0.3 s faster (run 6). The rule was that every v7 run beats the baseline run
  next to it, so the change was reverted and never committed.
- The inserts alone were 3.5 s faster, 0.1 s slower and 2.2 s faster: far less than the 21 s the
  throwaway probe of part A suggested. The parsing outside the inserts was 0.5-2.3 s slower in each
  pair, which would fit the generator's three random draws per id under a lock against
  `randomUUID()`'s one, but that was not measured. Why the probe's gain does not carry over was not
  investigated. All runs answered 201 within the 120 s target.
- Kept out of the repository (session scratch): the runner script, the series summary each run
  printed, and the generator's source with a note on how it was wired. The per-run logs and the
  patch were under target/ and a later clean build deleted them.

## Phase 4.1 — part A: bulk write path (NFR-PERF-1)

**Outcome: target missed by every form; no code change committed.** The statement form stays as it
is. Neither variant was faster than it, so neither is kept, and no privilege or schema change is
needed.

### What reaches PostgreSQL today

`JdbcStatementStore` sends one statement per 1,000 lines:
`INSERT INTO psp_lines … SELECT … FROM unnest(13 array parameters) WITH ORDINALITY … ORDER BY
position ON CONFLICT (source_code, line_id) DO NOTHING RETURNING id`, so 1,000 statements per
million lines, all in the ingestion transaction. No JDBC batch (`addBatch`/`executeBatch`) is used
for lines, and the datasource URL sets no driver options, so `reWriteBatchedInserts` is off and has
nothing to rewrite. Duplicates are found by `RETURNING id`, never by update counts: a line whose id
is not returned was skipped, and one more statement reads the stored line it met.

### Protocol

`.\mvnw.cmd -q -B test -Pperf "-Dtest=StatementIngestionPerformanceTest"` (`-Xmx512m`), one run at
a time, variants interleaved with the baseline in one sitting (2026-10-02, 14:27-14:59). Machine as
in Phase 4: Ryzen 5 7535HS (6 cores / 12 threads), 15.2 GB, Windows 11 Pro 10.0.26200, Temurin
21.0.12, `postgres:16-alpine` in Docker Desktop, default configuration. Also running: Chrome and
WhatsApp (idle); CPU load 0-9 % at each run's start. No other container ran (the ledger's are
created but stopped). Columns are the test's own breakdown: everything before parsing starts
(receive + hash), parsing with the inserts it drives, the inserts alone, everything after the file
row is written (commit + response).

| Run (in order) | Form | Total | Receive + hash | Parse with inserts | of which inserts | Commit + response | Peak heap |
|---|---|---|---|---|---|---|---|
| 1 | baseline | 110.509 s | 2.409 s | 79.275 s | 70.486 s | 28.785 s | 111 MB |
| 2 | variant 1 | 113.718 s | 2.269 s | 81.008 s | 73.378 s | 30.420 s | 116 MB |
| 3 | baseline | 106.103 s | 2.222 s | 74.774 s | 67.098 s | 29.094 s | 133 MB |
| 4 | variant 1 | 109.641 s | 2.254 s | 78.987 s | 71.605 s | 28.387 s | 126 MB |
| 5 | baseline | 105.075 s | 2.237 s | 73.520 s | 66.082 s | 29.303 s | 111 MB |
| 6 | variant 1 | 107.494 s | 2.225 s | 77.809 s | 70.565 s | 27.449 s | 116 MB |
| 7 | variant 2 | 109.861 s | 1.433 s | 80.170 s | 74.953 s | 28.248 s | 138 MB |
| 8 | baseline | 97.749 s | 1.487 s | 68.044 s | 63.076 s | 28.207 s | 123 MB |
| 9 | variant 2 | 108.107 s | 1.473 s | 79.358 s | 74.440 s | 27.267 s | 133 MB |
| 10 | baseline | 96.216 s | 1.445 s | 67.748 s | 62.940 s | 27.013 s | 145 MB |
| 11 | variant 2 | 109.757 s | 1.536 s | 80.904 s | 75.879 s | 27.307 s | 128 MB |

- **Baseline** (five runs): 96.2-110.5 s. The same code drifted by 14 s within the sitting, so each
  variant is compared with the baseline run next to it.
- **Variant 1, explicit multi-row `VALUES`** (`INSERT … VALUES (13 ?) × 1,000 ON CONFLICT DO
  NOTHING RETURNING id`, positional binds): 2.4-3.5 s slower than the baseline run before each.
  Driver-side rewriting was not tried as such: it applies only to a JDBC batch, which this path
  does not use, and turning the inserts into one would report `SUCCESS_NO_INFO` per row and lose the
  per-line answer that duplicate detection needs. Multi-row `VALUES` is what rewriting would send,
  with `RETURNING` kept. Measured only; the store tests were not run on it.
- **Variant 2, `COPY FROM STDIN`** into a `CREATE TEMPORARY TABLE IF NOT EXISTS … ON COMMIT DROP`
  staging table on the transaction's connection (`PGConnection.getCopyAPI()`), `TRUNCATE` before
  each batch, then `INSERT … SELECT … ORDER BY position ON CONFLICT DO NOTHING RETURNING id`: 10-14 s
  slower than the baseline run before it, all of it in the inserts (three round trips per batch
  instead of one, and every row written twice). It needed `GRANT TEMPORARY ON DATABASE` for
  recon_app (an uncommitted V11). With it, `StatementStoreTest`, `IngestStatementTest`,
  `StatementUploadTest` and `FailureMidFileTest` passed, duplicates across files included. It
  returned `id`, not `line_id`: a line id repeated within one batch is stored once, and only the id
  says which of the two lines was stored. The escaping round-trip test was not written, because the
  variant is not kept. The grant, the store change and the COPY encoder were removed from the working
  tree.
- **Neither variant met 60 s in any run**, so both are measured and reported, not committed.

### Variant 3: what the deferred line-to-file key costs at commit

Throwaway `postgres:16-alpine` containers, no published port, psql through `docker exec`, a fresh
container per probe, removed afterwards. Each probe creates the V2 `psp_lines` DDL (every CHECK, the
primary key and the unique constraint) and inserts 1,000,000 synthetic rows generated on the server
in 1,000 statements of 1,000, in one transaction. No client is involved, so these are PostgreSQL's
own costs. Script and runner: kept out of the repository (session scratch).

| Probe | Round 1 inserts / commit | Round 2 inserts / commit | WAL |
|---|---|---|---|
| key deferred, file row last (today) | 55.6 s / 25.8 s | 68.6 s / 26.2 s | 412 MB |
| key immediate, file row first | 98.7 s / 0.03 s | 110.3 s / 0.03 s | 412 MB |
| no key (measurement only) | 54.1 s / 0.02 s | 67.9 s / 0.02 s | 411 MB |
| no key, ids ascending instead of random v4 | — | 46.9 s / 0.02 s | 398 MB |

Round 1 ran deferred, immediate, no key; round 2 the reverse, then ascending ids. Four more deferred
probes (two with `shared_buffers=1GB`) gave 68.9 / 77.6 / 77.6 / 70.7 s of inserts and
26.0-28.0 s at commit. During one, `docker stats` showed the container at 100-107 % CPU, one backend
running with no wait event, no block read and 1.3 GB written.

- **The commit-time cost is the foreign key itself: 25.8-28.0 s per million lines in seven probes**,
  matching the 27.0-30.4 s commit stage of the perf test. Deferred, each inserted row queues an
  after-trigger event, and at commit PostgreSQL runs the key's check trigger once per row: one SPI
  query (`SELECT 1 FROM statement_files WHERE id = $1 FOR KEY SHARE`) each, about 26 µs, in the
  one backend, on CPU. PostgreSQL 16 has no set-based check for rows inserted under an existing key.
  With the key deferred, the inserts themselves cost the same as with no key at all.
- **Checking the key immediately is dearer, not cheaper:** 42.6 and 42.4 s over no key, against
  26 s deferred. Writing the file row first would also mean writing it before its final state,
  which V6's grants forbid. V10's deferral is therefore the cheapest form measured; no change to
  the write order or to V10 is proposed.
- **The inserts are CPU-bound in one backend, not I/O-bound.** WAL is 412 MB, below `max_wal_size`
  (no checkpoint started for WAL during any probe), and a larger `shared_buffers` made no
  difference, so no server setting is proposed. Durability settings were neither changed nor tested.
- The same probe with no key took 54.1 s in one round and 67.9 s in the other, so the VM drifts
  by about 25 % between containers. Every comparison above is within one round.

### Where that leaves NFR-PERF-1

Even with no key, PostgreSQL alone takes 47-68 s for the rows (including generating them on the
server), the Java side takes 6-11 s (receive and hash 1.4-2.4 s, parsing outside the inserts
4.8-8.8 s), and the key adds 26 s at commit. **60 s is not reachable on this machine with every
constraint kept and the file written in one transaction on one connection**, whatever form the
statements take. I stopped optimizing there, as the phase prompt says.

### Proposals for the owner (nothing committed)

1. **Time-ordered line ids.** Lines get `UUID.randomUUID()` (v4). Ascending ids made the probe's
   inserts 21 s faster (46.9 s against 67.9 s, same round): random keys scatter writes across the
   primary key index. A time-ordered (v7-style) id, generated by the application, changes no schema,
   constraint or setting, but its first 48 bits tell when the line was stored, and Java 21 has no v7
   generator, so it would be about 20 lines of our own code. Measured in a probe only, never through
   the perf test. It does not reach 60 s on its own: about 47 + 26 + 6-11 = 79-84 s.
2. **The target or its conditions** (TDD §4.6), since no write form reaches it here. A figure for
   this machine and setup would be about 96-111 s today (this sitting), or about 80-85 s with
   proposal 1, before drift.
3. **No privilege change.** TEMPORARY stays withheld from recon_app, since variant 2 is not kept.

### What the owner decided (TDD v1.7)

The target is 120 s and the `unnest` form stays; no other write form is tried. Proposal 1 was tried
in the second session and not kept (A2 above). TEMPORARY stays withheld from recon_app.

## Carried out of Phase 4.1

- The upload directory must be one instance's own: a second instance started on the same directory
  deletes the first one's part files in flight. Nothing checks for that
- Startup resolves the real paths of `java.io.tmpdir` and `user.home` to compare them with the
  upload directory; it lists nothing outside the upload directory
- The size re-check runs on the hashing pass only. The parse pass reads the same spooled file again
  and does not count, on the ground that the container writes its part file only while the body
  arrives; nothing re-hashes or re-counts the file after it was hashed

## Phase 4 — done

- [x] Parsers for both formats behind `StatementParser`, every TDD 7.3 code, strict UTF-8, bounded
  lines, BOM, CRLF; one record per physical line, so a quoted line break is `INVALID_FORMAT`
- [x] `IngestStatement`: hash before parsing, duplicate hash or reference refused with the original's
  id (FR-ING-3), lines stored in batches of 1,000 inside one transaction with the file row last
  (V10 defers the line-to-file check to commit), DUPLICATE_LINE break on the stored line or the
  existing unresolved break recorded instead (FR-ING-4, INV-7), invalid-line threshold in basis points
- [x] Security baseline (TDD 11.1): HTTP Basic, two users (OPERATOR implies VIEWER) with bcrypt
  hashes from the environment; a missing, malformed, "system" or shared user stops startup; sessions
  never created; 401 and 403 as Problem Details. `ops/security/HashPassword.java` makes a hash
- [x] CSRF option A: `CrossSiteRequestFilter` refuses a state-changing request whose
  `Sec-Fetch-Site` is not same-origin or none, whose `Origin` is `null` or not this server's, or that
  repeats either header. The server's origin comes from `server.address` and the bound port
- [x] `POST /api/v1/statements` (OPERATOR) and `GET /api/v1/statements/{id}` (VIEWER): 201, 422 with
  line numbers and codes, 409 with the original's id, 413, 400, 500, 401, 403; no error body carries
  a stack trace, SQL, a path or the file's name (`LeakCheck`)
- [x] Limits under `recon.ingestion.*`, each set once: `max-file-size` and `temp-directory` go to the
  servlet container (threshold 0, nothing in memory), the line limits to the parsers
- [x] Proofs: limits at their boundaries, no temp file after any outcome, a failure mid-file and
  after the file row both leave nothing and the same file then succeeds, the rollback's break proof
- [x] NFR-PERF-1 measured under `-Pperf` with `-Xmx512m`: 97.1 / 98.3 / 100.0 s for 1,000,000
  lines, peak heap 147-155 MB. **Target 60 s not met.** About 64 s is the line inserts and 27 s the
  commit; the perf profile fails on the assertion until the target is met

## Carried into later phases

- **TDD text (owner):** 413 for too many lines (§4.2 and the Phase 4 status list); no REJECTED row
  for an infrastructure failure (§5.3 step 5 says one is recorded); one record per physical line,
  a quoted line break being `INVALID_FORMAT` (§7); ISO-only currency for bank lines in §7.2, as §7.1
  and §7.3 already say for PSP lines
- **NFR-PERF-1 (owner):** the database is the cost, not the parser or the heap. Options are in the
  Phase 4 report; none is taken, since each changes a committed design decision or the TDD's batch
  size
- Phase 7: every new endpoint needs its own role rule in `SecurityConfiguration`; anything not named
  there needs only authentication. `/api/v1/statements` is the pattern
- Phase 9: METRICS for prometheus (any authenticated user reads it until then); a correlation id per
  request; the threat model must cover `.env` holding the user hashes in single quotes
- A browser at `http://localhost:8090` is refused for every POST: the configured origin is
  `http://127.0.0.1:8090`. PowerShell and curl send no `Origin` and are unaffected
- Test contexts share one PostgreSQL; the test profile caps each pool at four connections and keeps
  one once idle. A new context with its own `@DynamicPropertySource` is a new pool
- ~~The ArchUnit fixture controllers under `archfixture` are picked up by component scan in every
  test context~~ Resolved in Phase 4.1: the fixtures live under `com.baran.archfixture`

# Phase 3 record

## Phase 3 — done

- [x] `LoopbackContainers.kafka()` (`apache/kafka:4.3.1`), seen by `ContainersBindToLoopbackTest`;
  `support/ReconKafka` shares one broker per JVM and plays the ledger's relay
- [x] ArchUnit: `org.postgresql` only in `adapters.out.persistence`; Kafka's producer API only in
  `adapters.in.kafka` (INV-9, compile-time half). Both with fixtures that fail them
- [x] INV-9 run-time half: `DeadLetterOnlyProducer` wraps every producer of the application's factory
  and refuses any topic but `recon.ledger-account-activity.dlq`. Break proof:
  `LedgerTopicWriteGuardTest.withoutTheGuardTheSendLands`
- [x] `recon.sources` (code, type, ledger-accounts only), `recon.value-date-zone`,
  `recon.supported-currencies`; a contradictory configuration stops startup
- [x] `ProjectLedgerEvents`: one transaction per polled batch behind a `Transactions` port; FR-LED-8
  rolls the batch back and redoes it one entry per transaction. `LedgerEntryStore.storeAllIfAbsent`
- [x] `LedgerRecordParser`: strict UTF-8, duplicate keys refused, the committed schema (copied onto the
  classpath by the build, pinned byte for byte). Codes `SCHEMA_INVALID`, `CREATED_AT_NOT_A_DATE`,
  `INVALID_EVENT_ID` (absent / repeated / malformed named in the message), `DUPLICATE_ENTRY_ID`
- [x] `DeadLetterPublisher`: original key, value, headers kept; `x-error-code`, `x-error-message`,
  `x-original-topic`, `x-original-partition`, `x-original-offset` added; send awaited
- [x] `LedgerEventListener`: batch, `AckMode.MANUAL`, project → dead-letter → acknowledge.
  Infrastructure failures retried without limit (0.5 s doubling to 30 s), ERROR on every attempt
  with the backoff state, exception class names only
- [x] FR-LED-9 and TDD 6: an unmapped `tx_type` or an ISO currency outside the supported set is
  projected, WARNed once per value, counted (`recon_ledger_unmapped_tx_type_total{tx_type}`,
  `recon_ledger_unsupported_currency_total{currency}`); `ABC` stays `SCHEMA_INVALID`
- [x] NFR-PERF-3 measured under `-Pperf`: 6,291-6,561 events/s over three runs (target 5,000)

## Carried into later phases

- **TDD text (owner):** FR-LED-5 renames `MISSING_EVENT_ID` to `INVALID_EVENT_ID`; FR-LED-5's header
  list gains `x-original-partition`
- FR-LED-4 rests on three mechanisms, each with a permanent break proof: the projection failure
  propagates out of the listener (`SwallowedFailureBreakProofTest`), the error handler retries
  without limit (`OffsetCommitBreakProofTest`), and the listener acknowledges only after the commit,
  which matters when the container stops mid-outage (`EarlyAcknowledgeBreakProofTest`).
  `containerCommitsOnlyAfterTheListener` pins `AckMode.MANUAL` and auto-commit off; changing either
  reopens the gap. A change to the listener must keep all three
- Dead letters are at-least-once: a retried batch or a replay from offset 0 writes them again
- A test context that starts the listener (`ReconKafka.registerListening`) must be `@DirtiesContext`:
  every listening context joins the group `settlement-reconciliation` and takes partitions
- `support/ReconKafka` creates `ledger.account-activity` with 3 partitions when the shared broker
  starts; a test must not rely on Kafka's auto-creation, which makes one partition
- Phase 9: `recon_ledger_events_total{result}` (TDD 12) and a correlation id per consumed record
  (MDC) are not built yet
- Phase 4 can read `SupportedCurrencies` for `UNSUPPORTED_CURRENCY`
- A Kafka test container once exited with code 126 at startup and did not recur in the following
  runs; if it comes back, look at the image's start-script handoff before anything else

# Phase 2 record

## Phase 2 — done

- [x] Domain (pure Java, no framework): `Money`/`CurrencyCode` (exact, overflow throws, jqwik),
  `BusinessCalendar` (jqwik), items (`LedgerEntry`, `PspLine`, `BankLine`, `BatchKey` with a
  name-based UUID), `Match` (rule fixes cardinality and confidence, canonical item order), the break
  state machine (`Break`, `BreakAction`, `Transition`), `StatementFile`, `ReconciliationRun`
- [x] Migrations V2-V4: every table of TDD 10, every constraint and index named, one rule per CHECK;
  INV-2 and INV-7 partial unique indexes; append-only triggers (SQLSTATE RC001) on the event tables
- [x] Migrations V5-V9: each table opened to `recon_app` with the repository that uses it, for the
  verbs that repository issues; `breaks` UPDATE on three columns only; event tables SELECT/INSERT
- [x] Repositories behind `application.port` interfaces (`LedgerEntryStore`, `StatementStore`,
  `RunStore`, `MatchStore`, `BreakStore`), JDBC batches of 1,000 for lines
- [x] Proofs: `DatabaseMechanism` lists every constraint, unique index and trigger; the catalog test
  fails on one it lacks. `ApplicationRoleGrantsTest` pins exact grants; `WithheldPrivilege` proves
  each refused verb; INV-6 is proven as two separate defences (42501, then RC001)
- [x] `coverage.enforce=true`

## Carried out of Phase 2

- **Phase 3 exit criterion (owner):** INV-9 Kafka rule — done in Phase 3
- `prometheus` is unauthenticated until Spring Security lands (TDD 11.1 wants a METRICS role)
- UPDATE grants land with the code that issues them: `reconciliation_runs` and `sources_state`
  (Phase 5 run orchestration), `matches.status` and `match_items.active` (Phase 7 reversal). Each
  needs its `ApplicationRoleGrantsTest` line and, where a verb stays withheld, a `WithheldPrivilege`
- A new constraint, index or trigger needs a `DatabaseMechanism` entry, or the catalog test fails
- Phase 4 makes a file and its lines one transaction (FR-ING-6); `StatementStore` leaves that to
  the caller, and turns a duplicate line into DUPLICATE_LINE handling (FR-ING-4)
- Phase 5 fixes which configuration keys a run snapshots (`ReconciliationRun.configSnapshot`)

# Phase 1 record

## Phase 1 — done

- [x] Maven wrapper build on Spring Boot 4.1.1, profiles `local` and `test`, `.env.example`
- [x] `docker-compose.yml`: PostgreSQL on `127.0.0.1:5434`, Kafka on `127.0.0.1:9094`, named
  volumes, healthchecks. `ops/postgres/init/01-create-roles.sh` creates `recon_migrator` and
  `recon_app` with passwords from `.env`, as psql variables
- [x] `V1__baseline.sql`: Flyway (as `recon_migrator`) creates schema `recon`; `recon_app` gets
  CONNECT and USAGE only. It cannot run DDL: `ApplicationRoleCannotRunDdlTest`
- [x] Contract test over `contracts/samples` with the `networknt` validator
- [x] ArchUnit rules (TDD 5.2, INV-8, the code half of INV-9) with fixture trees that break each
- [x] `ci/check-rules.sh`, JaCoCo (measured; enforced from Phase 2 via `coverage.enforce`),
  GitHub Actions
- [x] Test containers on 127.0.0.1 only (`support/LoopbackContainers`), Ryuk disabled, never reused;
  `support/ReconPostgres` bootstraps test databases with the real init script
- [x] Actuator: `health`, `info`, `prometheus` only
- [x] Break proofs in `docs/break-proofs.md`. From this phase on, a proof is a permanent test where
  the broken state can be built without editing a file (`*BreakProofTest`,
  `ContainersBindToLoopbackTest.aPortOffLoopbackIsReported`). Ryuk is recorded as unproven, guarded
  by a `require` rule in `ci/check-rules.sh`

# Phase 0 record

## Done in Phase 0

- [x] Repository hygiene: `.gitignore` (build output, IDE, `.env`, `.phase-reports/`, owner-local
  material), `.editorconfig`. `.gitattributes` already existed and was left alone
- [x] Read `..\ledger-payment-core` read-only: no build, no test run, no git command in it
- [x] `docs/ledger-integration-notes.md`: stack, money, accounts, transactions, the full event
  contract (topic, key, `event-id` header, payload, delivery semantics), what the event does **not**
  carry, entry identity, images and ports, six TDD corrections, four risks, five open questions
- [x] `contracts/ledger-events.schema.json` (draft 2020-12), 4 valid and 7 invalid samples, and
  `contracts/README.md` carrying the `event-id` header contract beside the payload schema. Both
  validation runs executed: valid pass (exit 0), invalid all fail (exit 1)
- [x] `docs/adr/0001-separate-service-and-repository.md`
- [x] `docs/adr/0002-json-schema-contract-instead-of-schema-registry.md`
- [x] `.phase-reports/phase-0-report.md` (not committed; `.phase-reports/` is ignored)

No application code, build file or Docker file was written, which is this phase's constraint.

## Open questions carried out of this phase

Recorded in `docs/ledger-integration-notes.md` and in the report. None is resolved here, and Phase 1
must not assume an answer.

1. **OQ-3** PSP clearing account: which ledger account type represents it
2. **OQ-4** Whether Phase 8 produces ledger data through the ledger API or as synthetic events
3. **OQ-5** License: the ledger has none to match

## Decisions taken after the phase report

- **OQ-1 and OQ-2 resolved by the ledger change.** The owner added `entry_id` and `created_at` to
  the account activity event in `..\ledger-payment-core`: commit `e3119e9`, merged to its `main` as
  `93eadc2`. `entry_id` is a JSON integer (`ledger_entries.id`); `created_at` is the entry's column,
  UTC, always six fractional digits. Events written before that commit carry only five fields and
  are still on the topic. What this service does, recorded in `docs/ledger-integration-notes.md` §6
  "Ledger change landed":
  - `value_date` derives from `created_at` in `Europe/Istanbul`, as TDD §6 writes it
  - `entry_id` is the projection's entry identity (partial unique index on `ledger_entry_id`)
  - the `event-id` header stays the deduplication key
  - the same `entry_id` under a different `event-id` is a ledger fault: rejected by that index,
    logged at `ERROR`, dead-lettered
  - five-field history is stored with no value date, never back-dated from the record timestamp,
    outside every run's scope, never a break, and counted separately in the summary report
- **OQ-6 resolved — no npm registry, no `npx`.** Contract verification is a Maven test added in
  Phase 1 that runs the samples through `contracts/ledger-events.schema.json` with the `networknt`
  JSON Schema validator, as part of `mvnw.cmd verify`. `contracts/README.md` no longer documents a
  command to run by hand. Phase 0's own verification was done with `ajv-cli` before this rule was
  settled, and that result is recorded rather than repeated

## Contract update after the ledger change (2026-09-25)

- [x] `contracts/ledger-events.schema.json`: seven fields, five required. `entry_id` and
  `created_at` optional but paired (`dependentRequired`); absent is valid, `null` is not.
  `tx_type` is an open non-empty string instead of an enum
- [x] Samples: 7 valid (including a five-field one and an unmapped `FEE`), 15 invalid. Every file
  parses as JSON and was reviewed by hand against its expected result. **No schema validator has
  run over them yet**: that waits for Phase 1's `networknt` Maven test
- [x] `docs/adr/0002-…`: dated amendment reversing the closed `tx_type` enum; original text kept
- [x] `docs/ledger-integration-notes.md`: new-field evidence cited at ledger `93eadc2`, §5.5 on
  unmapped `tx_type` values, OQ-1 and OQ-2 resolved
- [x] Proposed TDD v1.2 text for §6 and §10 in
  `.phase-reports/phase-0-addendum-ledger-contract-report.md`. The TDD is still v1.1 until the
  owner applies it

Phase 2's `ledger_entries` migration is no longer blocked on the ledger. It follows TDD §10 once the
owner has applied the v1.2 text.

## Next phase

Phase 2 — domain model and persistence. Migrations continue from `V2`; each new table grants
`recon_app` exactly the verbs the code issues, and audit tables SELECT and INSERT only (TDD 8.4).
