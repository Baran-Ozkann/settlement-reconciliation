# Progress

**Current phase:** 1 — Project skeleton (complete, pending the owner's review)
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-09-25

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

## Carried into later phases

- **Phase 3 exit criterion (owner):** INV-9 Kafka rule. No producer can write to a ledger topic,
  only the DLQ topic; enforced by a rule with a fixture that fails it, plus a break proof
- `prometheus` is unauthenticated until Spring Security lands (TDD 11.1 wants a METRICS role)
- `coverage.enforce` must be set to `true` in Phase 2

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
