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
