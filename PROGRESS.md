# Progress

**Current phase:** 3 — Ledger event consumer (complete, pending the owner's review)
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-09-26

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
- The acknowledge call's position in `LedgerEventListener.onBatch` is **Unproven** in
  `docs/break-proofs.md`: under `AckMode.MANUAL` moving it first is invisible to the suite.
  `containerCommitsOnlyAfterTheListener` pins `MANUAL` and auto-commit off instead
- Dead letters are at-least-once: a retried batch or a replay from offset 0 writes them again
- A test context that starts the listener (`ReconKafka.registerListening`) must be `@DirtiesContext`:
  every listening context joins the group `settlement-reconciliation` and takes partitions
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
