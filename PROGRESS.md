# Progress

**Current phase:** 0 — Discovery and contract extraction (complete)
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-09-24

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

1. **OQ-1** `value_date` of a ledger entry: the event carries no `created_at`. Five options with
   their costs are listed in the notes. Blocks the projection's value-date column, and with it the
   date-window part of Stage A
2. **OQ-2** Entry identity: the event carries no ledger entry id. Decides the projection's primary
   key and what INV-3 asserts
3. **OQ-3** PSP clearing account: which ledger account type represents it
4. **OQ-4** Whether Phase 8 produces ledger data through the ledger API or as synthetic events
5. **OQ-5** License: the ledger has none to match

## Decisions taken after the phase report

- **OQ-6 resolved — no npm registry, no `npx`.** Contract verification is a Maven test added in
  Phase 1 that runs the samples through `contracts/ledger-events.schema.json` with the `networknt`
  JSON Schema validator, as part of `mvnw.cmd verify`. `contracts/README.md` no longer documents a
  command to run by hand. Phase 0's own verification was done with `ajv-cli` before this rule was
  settled, and that result is recorded rather than repeated

## Next phase

Phase 1 — project skeleton. It starts from an empty build: there is no `pom.xml`, no
`docker-compose.yml` and no source tree yet, by design. Two Phase 0 findings feed it directly: the
port map to avoid (notes section 8) and the broker recommendation (notes section 9).
