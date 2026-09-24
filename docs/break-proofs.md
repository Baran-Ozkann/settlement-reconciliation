# Break proofs

A green test proves nothing on its own (TDD §9.1). Each row below is a mechanism that was broken on
purpose in a throwaway change that was **never committed**, the test that should catch it run
against the broken tree, and the failure it reported. The mechanism was then restored and the same
test run green again before anything was committed.

Each phase adds the rows for the mechanisms it introduces.

## Phase 1

| Mechanism | Broken by | Test | What it reported |
|---|---|---|---|
| `dependentRequired` pairing `entry_id` and `created_at` (FR-LED-6) | `dependentRequired` deleted from `contracts/ledger-events.schema.json` | `LedgerEventContractTest` | 2 of 24 failed: `invalid-created-at-without-entry-id` and `invalid-entry-id-without-created-at` each `Expected size: 1 but was: 0` — both validated |
| New fields optional, so five-field history stays valid (FR-LED-7) | `entry_id` and `created_at` added to `required` | `LedgerEventContractTest` | 10 of 24 failed. `valid-five-field-written-before-entry-reference`: `required property 'entry_id' not found`. The five-field invalid samples each reported 3 errors instead of 1, and the two pairing samples 2, so each failed for the wrong reason as well |
| `tx_type` open, not an enum (FR-LED-9) | `enum: [TRANSFER, FUNDING, REVERSAL]` put back on `tx_type` | `LedgerEventContractTest` | 2 of 24 failed. `valid-tx-type-unmapped-fee`: `/tx_type: does not have a value in the enumeration`; `invalid-tx-type-not-a-string` gained a second error, so it no longer failed on `type` alone |
| INV-8 floating-point condition sees calls, not only fields and signatures | the method-call scan in `ArchitectureRules.notUseFloatingPoint` replaced by an empty list | `ArchitectureRulesCatchViolationsTest` | 1 of 17 failed: `[doublecall is rejected]` — `Math.round(minorUnits / 2.0)` in a domain class passed |
| TDD 5.2 domain depends only on `java..` and itself | `org.springframework..` added to the domain rule's allowed packages | `ArchitectureRulesCatchViolationsTest` | 1 of 17 failed: `[domainspring is rejected]` — a domain class calling `StringUtils.hasText` passed |
| `ci/check-rules.sh` deny rules | one throwaway run with a planted violation for each: `double`, `BigDecimal` and a `TODO` in a domain class; `new GenericContainer<>(…).withReuse(true)` and `Ports.Binding.bindIp("0.0.0.0")` in a test class; the `127.0.0.1:` prefix removed from the PostgreSQL port in `docker-compose.yml` | `bash ci/check-rules.sh` | exit 1, seven `RULE VIOLATION` lines: floating point in the money path, BigDecimal outside the file-parsing adapter, TODO/FIXME, reuse switched on, container built outside `LoopbackContainers`, port bound beyond loopback, compose port published beyond loopback. Each named the planted file and line |
| `ci/check-rules.sh` guard: a search that could not run fails | `src/main/java/com/baran/recon/application` moved away | `bash ci/check-rules.sh` | exit 1, `GUARD ERROR: could not run check 'floating point in the money path (domain, application)' (exit 2)` / `grep: …/application: No such file or directory`. Treating exit 2 as clean would have passed here |
