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

### Proven by permanent tests

For these mechanisms the break proof is a test in the suite rather than a recorded one-off. The
test builds the broken state itself, in a throwaway container or an application context of its
own, and asserts that the check reports it. No committed file is edited to break anything, and the
proof runs on every build instead of once.

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| Test containers publish on 127.0.0.1 only (`LoopbackContainers`, checked by `ContainersBindToLoopbackTest.everyPublishedPortIsOnLoopback`) | a throwaway PostgreSQL container whose binding is overridden to `127.0.0.2`: loopback, so nothing leaves the machine, but not the address the factory chooses. Docker's default of every interface is not used, because publishing there is what CLAUDE.md 3.2 forbids | `ContainersBindToLoopbackTest.aPortOffLoopbackIsReported` | the same daemon-side check reports that container's port, on `127.0.0.2` |
| `recon_app` cannot run DDL (bootstrap roles + `V1__baseline.sql`, checked by `ApplicationRoleCannotRunDdlTest`) | in a throwaway database, one transaction per statement: the privilege `ForbiddenDdl` names as withheld is granted (CREATE on `recon`, USAGE and CREATE on `public`, TEMPORARY or CREATE on the database, or ownership of the table or schema), then rolled back | `ApplicationRoleDdlBreakProofTest` | each of the 9 statements is refused with SQLSTATE 42501 before its grant and succeeds after it, so the absent grant is what refuses it |
| Actuator exposes only `health`, `info`, `prometheus` over HTTP (`application.yml`, checked by `ActuatorExposureTest`) | an application context of its own with `env` added to `management.endpoints.web.exposure.include` as a test property; no file is edited | `ActuatorExposureBreakProofTest` | `/actuator/env` answers 200, so the 404 `ActuatorExposureTest` expects comes from the exposure list |

### Unproven

| Mechanism | Why there is no break proof | What guards it instead |
|---|---|---|
| Ryuk disabled (`TESTCONTAINERS_RYUK_DISABLED=true` in the surefire configuration) | Ryuk publishes its port on every interface and has no setting to change that. Starting it to show the check catches it would publish on `0.0.0.0`, which CLAUDE.md 3.2 forbids, and it is enabled per JVM, not per container, so a test cannot start it for itself alone. `ContainersBindToLoopbackTest` would report its port if it ever ran, but that has not been observed | `require "Ryuk disabled for the test run"` in `ci/check-rules.sh` fails the build when the line is missing from `pom.xml` |

## Phase 2

Database mechanisms are proven through one list. `DatabaseMechanism` (test sources) names every
constraint, unique index and trigger in the `recon` schema, each with the rows it needs and a
statement that violates it and nothing else. `DatabaseMechanismTest` runs each twice on a throwaway
database, in a transaction that is rolled back: once to see the violation refused with that
mechanism's SQLSTATE and name, once with only that mechanism removed to see the same statement go
through. The second half is the break proof. It also shows the violation tests exactly one rule: a
statement that broke two would still fail with one of them removed.

A catalog test compares the list with `pg_constraint`, `pg_index` and `pg_trigger` in both
directions. A mechanism added without an entry, and so without a proof, fails the build.

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| Every constraint and unique index of `ledger_entries`, `statement_files`, `psp_lines`, `bank_lines` (V2): 5, 11, 13 and 10 of them, each a `DatabaseMechanism` entry | that one mechanism dropped (`ALTER TABLE … DROP CONSTRAINT … CASCADE`, or `DROP INDEX` for a partial unique index) inside a rolled-back transaction | `DatabaseMechanismTest.withoutThisMechanismTheViolationGoesThrough` | the statement `violationIsRefusedByThisMechanism` saw refused with that constraint's name now succeeds |
| Every constraint and unique index of `reconciliation_runs`, `sources_state`, `matches`, `match_items`, `match_events`, `breaks`, `break_events` (V3): 8, 3, 9, 4, 6, 13 and 10 of them, including the INV-2 index `match_items_active_item_unique` and the INV-7 index `breaks_one_unresolved_per_item` | as for V2 | `DatabaseMechanismTest.withoutThisMechanismTheViolationGoesThrough` | as for V2. What each partial index deliberately allows (a reversed match, a resolved break, five-field entries, a rejected file) is shown by `PartialUniqueIndexesTest` |
| INV-6 append-only triggers on `match_events` and `break_events` (V4): `*_append_only` for UPDATE and DELETE, `*_no_truncate` for TRUNCATE | the trigger disabled by the table owner inside a rolled-back transaction | `AuditTriggerTest.withoutTheTriggerTheOwnersChangeGoesThrough`, and the four `DatabaseMechanism` trigger entries | the owner, who holds UPDATE, DELETE and TRUNCATE, is refused with SQLSTATE RC001 naming the trigger while it is enabled, and makes the same change once it is disabled. The privilege half is proven with the grants |
| Grants: recon_app holds exactly the listed verbs per table, no column grants (`ApplicationRoleGrantsTest`) | an extra table grant and an extra column grant made inside a rolled-back transaction | `ApplicationRoleGrantsTest.anExtraGrantIsReported` | both extra grants are reported |
| Grants: each verb recon_app must never issue is refused (`WithheldPrivilege`), from V5 on: UPDATE, DELETE and TRUNCATE on `ledger_entries`; from V6, UPDATE and DELETE on `statement_files`, `psp_lines` and `bank_lines`; from V7, DELETE on `reconciliation_runs`; from V8, DELETE on `matches` and `match_items`, and UPDATE, DELETE and TRUNCATE on `match_events` (INV-6, the privilege half); from V9, DELETE on `breaks`, UPDATE of any `breaks` column but the three a transition changes, and UPDATE, DELETE and TRUNCATE on `break_events` (INV-6) | the one withheld grant made inside a rolled-back transaction, as superuser acting as recon_app through `SET ROLE` | `WithheldPrivilegeTest.theMissingGrantIsWhatRefusesIt` | refused with 42501 before the grant; after it the statement succeeds, or on an audit table is refused by the trigger (RC001) instead |
| NFR-TEST-1 coverage floors enforced (`coverage.enforce=true`: 90 % line coverage per `domain` package, 80 % overall) | a build that runs one test class only: `.\mvnw.cmd -B clean verify "-Dtest=MoneyTest"`, no file edited | the command itself (a one-off, since a test cannot run the build it is part of) | BUILD FAILURE, with `Rule violated` for each under-covered `domain` package, e.g. `domain.money: lines covered ratio is 0.81, but expected minimum is 0.90`. The full suite passes the same check: domain 99.5 %, overall 98.6 % |
| The catalog test (`everyMechanismInTheSchemaIsListed`) | an unlisted CHECK, partial unique index and trigger added inside a rolled-back transaction | `DatabaseMechanismTest.anUnlistedMechanismIsReported` | the catalog query reports exactly those three as unlisted |

## Phase 3

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| The PostgreSQL driver is referenced only from `adapters.out.persistence` (ArchUnit `postgresDriverOnlyInPersistence`) | two fixture trees, no application file edited: `postgreskafka` (a Kafka adapter class testing for `PSQLException`) and `postgresconfig` (a config class building a `PGSimpleDataSource`) | `ArchitectureRulesCatchViolationsTest.ruleRejectsItsFixture` | the rule reports each offending class. The clean tree's persistence class uses `PSQLException` and passes, so the rule allows the one place the driver belongs |
| INV-9, compile-time half: Kafka's producer API (`org.apache.kafka.clients.producer..`, `KafkaOperations`, `ProducerFactory`, `DeadLetterPublishingRecoverer`) is used only in `adapters.in.kafka` (ArchUnit `kafkaProducersOnlyInTheKafkaAdapter`) | two fixture trees: `ledgerproducer` (a persistence class sending to `ledger.account-activity` through a `KafkaTemplate`) and `rawproducer` (a config class building a raw `KafkaProducer`, bypassing Spring's factory) | `ArchitectureRulesCatchViolationsTest.ruleRejectsItsFixture` | the rule reports `EntryEcho` and `LedgerWriter`. The clean tree's Kafka adapter holds a `KafkaTemplate` and passes |
| INV-9, run-time half: every producer the application's factory creates is wrapped in `DeadLetterOnlyProducer`, which refuses a send to any topic but `recon.ledger-account-activity.dlq` (registered by a `DefaultKafkaProducerFactoryCustomizer` in `LedgerKafkaConfiguration`) | a factory built from the application's own producer properties (`KafkaProperties.buildProducerProperties()`) without the post-processor, pointed at a throwaway broker so nothing it writes reaches the shared test broker; no file is edited | `LedgerTopicWriteGuardTest.withoutTheGuardTheSendLands` | the send to `ledger.account-activity` that `templateRefusesTheLedgerTopic` and `factoryProducerRefusesTheLedgerTopic` see refused is acknowledged, and the topic holds one record: the post-processor is what stops it |
