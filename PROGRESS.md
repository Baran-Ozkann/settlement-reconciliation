# Progress

**Current phase:** 0 — Discovery and contract extraction (complete)
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-09-25

## Done in this phase

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

Phase 1 — project skeleton. It starts from an empty build: there is no `pom.xml`, no
`docker-compose.yml` and no source tree yet, by design. Two Phase 0 findings feed it directly: the
port map to avoid (notes section 8) and the broker recommendation (notes section 9).
