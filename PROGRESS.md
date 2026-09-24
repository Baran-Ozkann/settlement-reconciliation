# Progress

**Current phase:** 0 — Discovery and contract extraction
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-09-24

## Done in this phase

- [x] Repository hygiene: `.gitignore` (build output, IDE, `.env`, `.phase-reports/`, owner-local
  material), `.editorconfig`. `.gitattributes` already existed and was left alone
- [x] Read `..\ledger-payment-core` read-only: no build, no test run, no git command in it

## In progress

- [ ] `docs/ledger-integration-notes.md` — every finding with `file:line` into the ledger
- [ ] `contracts/ledger-events.schema.json`, `contracts/samples/`, `contracts/README.md`
- [ ] `docs/adr/0001-separate-service-and-repository.md`
- [ ] `docs/adr/0002-json-schema-contract-instead-of-schema-registry.md`
- [ ] `.phase-reports/phase-0-report.md` (not committed; `.phase-reports/` is ignored)

## Open questions carried out of this phase

Recorded in the notes and the report; none of them is resolved here.

1. `value_date` for a ledger entry: the event carries no `created_at`
2. Entry identity: the event carries no ledger entry id
3. PSP clearing account: which ledger account type represents it
4. Whether Phase 8 produces ledger data through the ledger API or as synthetic events
5. License

## Next phase

Phase 1 — project skeleton. Nothing in this phase writes application code, build files or
Docker files, so Phase 1 starts from an empty build.
