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
| FR-LED-4, the offset of a record whose transaction did not commit is never committed: the consumer's error handler retries a failing batch without limit (`ledgerConsumerErrorHandler`), checked by `LedgerEventConsumerTest.crashBeforeCommitIsRedelivered` | a context of its own in which a test bean post-processor replaces that handler with a bounded `DefaultErrorHandler` (three attempts, then the batch counts as handled, as Spring Kafka's default does), with the store failing every batch after its insert; no file is edited | `OffsetCommitBreakProofTest.withBoundedRetriesTheRecordIsLost` | after exactly three failed attempts the group's committed offset passes the record while no row for it exists: the record is lost. With the application's handler, the crash test sees the offset held back while the store fails |
| FR-LED-5, a record that cannot be projected does not block its partition | not a mechanism that can be removed: the listener dead-letters instead of throwing | `LedgerEventConsumerTest`, every dead-letter test | a valid record published after the invalid one on the same key, so the same partition, is stored |
| FR-LED-4, a projection failure propagates out of `LedgerEventListener.onBatch`. Under `AckMode.MANUAL`, Spring Kafka 4.1.1 commits a batch's offsets when the listener returns normally (`ListenerConsumer.doInvokeBatchListener` → `processCommits`) or after the error handler has handled what it threw (`commitOffsetsIfNeededAfterHandlingError`), which with unlimited retries means after a retry went through. A failure that never leaves the listener gives the error handler nothing to retry | a context of its own in which the application's listener is not started and a test-local variant consumes in its place: it delegates each batch to the application's listener bean and swallows what it throws; the store fails every batch after its insert. No application file is edited | `SwallowedFailureBreakProofTest.swallowedFailureLosesTheRecord` | the failing batch is tried once and never again; the next batch is acknowledged, the group's offset commits past the record, and no row for it exists. With the application's listener, `LedgerEventConsumerTest.crashBeforeCommitIsRedelivered` sees the same failure held back and redelivered |
| FR-LED-4, `LedgerEventListener.onBatch` acknowledges only after the projection has committed and the dead letters are written. Under `AckMode.MANUAL` an acknowledgement records the batch's offsets (`ListenerConsumer.processAcks` → `addOffset`) and nothing on the failure path discards them; a container that stops commits them on its way out (`wrapUp` → `commitPendingAcks`), which is what a shutdown during a database outage does | a context of its own in which the application's listener is not started and a test-local variant consumes in its place: it acknowledges each batch first, then delegates it to the application's listener bean; the store fails every batch after its insert, and the variant's container is stopped mid-failure. No application file is edited | `EarlyAcknowledgeBreakProofTest.earlyAcknowledgementIsCommittedOnStop`; the same stop with the application's listener is `LedgerEventConsumerTest.stopDuringAnOutageCommitsNothing` | with the acknowledgement first, the stop commits the group's offset past a record for which no row exists. With the application's listener, the same stop commits nothing for the failing batch, and after a restart the record is stored once. `containerCommitsOnlyAfterTheListener` pins `AckMode.MANUAL` and auto-commit off, the settings every FR-LED-4 proof assumes |

### Phase 3, correction

An earlier version of this section recorded the acknowledge call's position in `onBatch` as
Unproven. The throwaway edit moved `acknowledge()` first and ran the crash test of the time. That
test only checked the end state and never stopped the container, so the edit went unseen. The
entry also named the wrong mechanism: what keeps the offset back while the store fails is that the
failure propagates, proven above. The acknowledge position is a separate mechanism that matters when
the container stops mid-outage, and is proven above as well. Nothing in FR-LED-4 is Unproven.

## Phase 4

Every proof here is a permanent test; no mechanism of this phase was broken by a throwaway edit,
and none is Unproven. Where a check is a boundary (a size, a count, a role), the proof is the same
request on the other side of it: if the check went blind, the two would no longer differ.

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| FR-ING-9, structurally: domain, application, the web adapter and the parsers hold no filesystem API (ArchUnit `noFilesystemWhereTheUploadIsHandled`) | two fixture trees, no application file edited: `filenamepath` (a web class building `Path.of` from the client's file name) and `usecasefile` (a use case opening a `FileOutputStream` with it) | `ArchitectureRulesCatchViolationsTest.ruleRejectsItsFixture` | the rule reports `UploadStore` and `SpoolUpload`. The clean tree's configuration class creates a directory and passes, so the rule allows the one place that sets the temp directory up |
| V10: the line-to-file foreign keys deferred for the ingestion transaction still refuse, at commit, a line whose file row was never written | in a throwaway database, the foreign key dropped by its owner | `DeferredLineFileCheckTest.withoutTheForeignKeyTheOrphanCommits` | the same deferred orphan line that `orphanIsRefusedAtCommit` sees refused by the key's name commits |
| FR-ING-6, the rollback: a failure after the file row and every line are written leaves nothing (`FailureMidFileTest.failureAfterTheFileRowRollsBackAndTheSameFileThenSucceeds`). There every line has its file, so the deferred check cannot help, and only the rollback removes the ingestion | a context of its own in which the use case gets a `Transactions` that commits the work even when it throws, then rethrows; the store fails right after the file row, as in the test. No application file is edited | `FailureAfterFileRowBreakProofTest.withoutTheRollbackTheFailedIngestionIsKept` | the upload is still 500, but the file row is committed as INGESTED with all 2,500 lines. A failure mid-file (`failureMidFileRollsBackAndTheSameFileThenSucceeds`) is stopped by the rollback and the deferred check together |
| NFR-SEC-1: every endpoint but health needs an authenticated user (`SecurityConfiguration`) | the same requests with the operator's credentials | `AuthenticationRequiredTest.rightPasswordIsNot401`, against `actuatorWithoutCredentialsIs401` and `wrongPasswordIs401` | prometheus, 401 without credentials or with a wrong password, is 200 with the right ones: the 401 is the credentials' doing, not a missing endpoint's |
| TDD 11.1: only an OPERATOR uploads | the same upload as the operator | `StatementUploadTest.viewerUploadIs403` against `validPspFileIs201` | the viewer's upload is 403 and records nothing; the operator's is 201 |
| TDD 11.1: the users come from the environment, and a missing, malformed or shared user stops startup (`UsersProperties.validate`) | the context runner with two valid users | `SecurityUsersConfigurationTest.validUsersStart`, against each `...StopsStartup` case | two valid users start, so each refusal is the one value that differs from them |
| TDD 11.1 CSRF: a state-changing request a browser marks as cross-site is refused (`CrossSiteRequestFilter`) | a context of its own in which a bean post-processor takes the filter out of the application's security filter chain; no file is edited | `CrossSiteRequestBreakProofTest.withoutTheFilterTheRequestGetsThrough` | each of the eight requests `CrossSiteRequestTest.refusedWith403` sees refused (cross-site and same-site `Sec-Fetch-Site`, `Origin: null`, a foreign `Origin`, this server under another name, either header sent twice, a `Host` agreeing with a foreign `Origin`) reaches the application and ends in 404 |
| Actuator exposes only `health`, `info`, `prometheus`, now behind authentication | as in Phase 1, with the operator's credentials, the ones `ActuatorExposureTest` now sends | `ActuatorExposureBreakProofTest` | `/actuator/env` answers 200 once exposed, so the 404s `ActuatorExposureTest` sees, as the operator, come from the exposure list and not from a 401 or 403 |
| FR-ING-8: `recon.ingestion.max-file-size` reaches the servlet container (`IngestionConfiguration.multipartConfigElement`) | the context's limit set to 16 KB; a file of exactly 16 KB | `UploadLimitsTest.fileOfExactlyTheSizeLimitIsAccepted`, against `fileOverTheSizeLimitIs413` | 16,384 bytes are read and parsed; 16,385 are 413, never parsed, never recorded. `IngestionConfigurationTest.multipartSettingsBind` pins the values handed to the container |
| FR-ING-8: `recon.ingestion.max-lines` | the context's limit set to 5; a file of exactly 5 data lines | `UploadLimitsTest.exactlyTheLineLimitIsIngested`, against `oneLineOverTheLimitIs413` | 5 lines are ingested; 6 are 413 with no file row and no line recorded |
| FR-ING-8: `recon.ingestion.max-line-length` | a line of exactly the limit | `LineReaderTest.lengthLimitBoundary` | 4,096 bytes are read; 4,097 are `LINE_TOO_LONG`. `UploadLimitsTest.lineOverTheLengthLimitIs422` shows the code reaching the client with its line number |
| No temp file is left after any outcome, and nothing is buffered in memory (file size threshold 0) | not a mechanism that can be switched off: the container deletes its part files when the request ends. What could make the check blind is an upload kept somewhere other than the directory it watches, or kept in memory | `UploadLimitsTest.uploadIsKeptInTheConfiguredDirectory`, the `@AfterEach` of every `UploadLimitsTest` case, and the end of each `FailureMidFileTest` case | while a file is parsed, the context's own temp directory holds three container files, one per part, the two short text fields included, which a threshold above 0 would have kept in memory. After every outcome (201, 422, 409, 400, 413 for size and for lines, 401, 403, 500) the same directory is empty |
| FR-ING-4 line-level deduplication (`ON CONFLICT (source_code, line_id) DO NOTHING`) and INV-7 opening a break only when the item has none (`openUnlessUnresolved`) | — | — | rest on `psp_lines_source_line_unique`, `bank_lines_source_line_unique` and `breaks_one_unresolved_per_item`, each proven in Phase 2 by `DatabaseMechanismTest`. Without its unique constraint, an `ON CONFLICT` naming those columns fails outright instead of passing silently |

## Phase 4.1

Three proofs are permanent tests. The fourth, the REST controller check, is a recorded one-off: the
broken state is the history before the fixture move, not something a test can rebuild without
putting fixtures back into the application's package. Nothing is Unproven.

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| FR-ING-8, the application's own size check: the use case counts the bytes it reads from the spooled part and refuses past `recon.ingestion.max-file-size` with 413 (`IngestStatement.hash`), checked by `ApplicationFileSizeLimitTest.oneByteOverIs413` in a context whose servlet limit is four times the application's | the same context, with the servlet limit raised the same way, and in addition a bean post-processor replacing the use case's `IngestionLimits` with a copy whose file size limit is the servlet's. No application file is edited | `ApplicationFileSizeLimitBreakProofTest.withoutTheApplicationCheckTheFileIsIngested` | the valid three-line file one byte over the configured limit, which the test sees refused with 413, never parsed and never recorded, is ingested with 201 and its 3 lines; the temp directory is empty afterwards in both |
| The temp directory must be the application's own: startup stops on the system temp directory itself, a filesystem root or the user's home, as configured or through a link (`UploadDirectory.prepare`) | the same context with a directory of its own under the stand-in temp directory | `UploadDirectoryTest.dedicatedDirectoryStarts`, against `systemTempStopsStartup` (written three ways), `homeStopsStartup`, `rootStopsStartup` (the root as the OS names it and as `/`), `linkToRootStopsStartup`, `linkToSystemTempStopsStartup`, `blankStopsStartup` | the dedicated directory starts and is created; each refused one is the one value that differs. `java.io.tmpdir` and `user.home` are stood in by directories under target/, so nothing outside the build output is touched even if a guard failed. The three link cases run on Linux (CI, and a throwaway `eclipse-temurin:21` container: 10 of 10, none skipped); on this Windows machine they are skipped by an assumption, since the account may not create symbolic links. See the correction below |
| Startup deletes stale container part files (`upload_*.tmp`, regular files directly in the directory) and nothing else, checked by `UploadDirectoryTest.staleFileIsDeleted` and `onlyPartFilesDirectlyInTheDirectoryAreDeleted` | the same configuration, but a bean factory post-processor replaces the `uploadDirectory` bean, after the configuration is read, with one that is just the directory, neither checked nor cleaned. No application file is edited | `UploadDirectoryBreakProofTest.withoutTheCleanupTheStaleFileStays` | the stale part file is still there after startup: the cleanup removes it, not the container or the test. `linkIsNeitherFollowedNorDeleted` runs on Linux and is skipped on this Windows machine, as above |
| Every `@RestController` bean is in `com.baran.recon.adapters.in.web` (`StatementUploadTest.everyRestControllerIsInTheWebAdapter`), true since the ArchUnit fixtures moved to `com.baran.archfixture` | a recorded one-off: the assertion applied, uncommitted, to `e01b998` (the commit before the move) in a throwaway worktree under target/, removed afterwards | `.\mvnw.cmd -q -B test "-Dtest=StatementUploadTest#everyRestControllerIsInTheWebAdapter"` there | failed, naming `com.baran.recon.archfixture.clean.adapters.in.web.TotalsController`, `...controllerjdbc.adapters.in.web.QueryingController` and `...controllerrepository.adapters.in.web.RepositoryController` beside `StatementController`. At `e915b96` it passes. `ArchitectureRulesCatchViolationsTest` still sees every rule reject its fixture (26 cases) |

### Phase 4.1, correction

The first version of the root guard's row was green for the wrong reason on Windows and red on
Linux. `recon.ingestion.temp-directory` was bound as a `Path`, and Spring's `PathEditor` first tries
such text as a resource location: a configured `/` named the classpath root and was bound as
`target/test-classes`. The guard therefore never saw `/`, accepted a directory nobody had named,
and the startup sweep ran over it. CI on Linux reported `rootStopsStartup` starting successfully;
on Windows the test wrote `C:\`, which is not a resource location, and passed. A throwaway probe in
an `eclipse-temurin:21` container printed `configured=[/] PathEditor gives
[/work/target/test-classes]` and the context's upload directory as that same path. The guard itself
was right. `df08898` binds the property as text and makes the path with `Path.of`. The test now
writes the root both ways: run against the old binding it failed on Windows (`/`) and on Linux, and
it passes on both after the fix.

## Phase 5, part 1a

Every proof here is a permanent test. Nothing is Unproven. Two mechanisms of the run lifecycle have
no break proof yet: the rollback of the work transaction and the startup recovery. They are owed
in part 1b, when the work transaction writes matches and breaks (see PROGRESS.md). Both are proven
in part 1b, below.

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| TDD 11.1: no `@ConfigurationProperties` type, nor a type nested in one, has a `java.nio.file.Path` component, directly or inside a generic type (`ArchitectureRules.noPathBoundFromConfiguration`) | the fixture tree `com.baran.archfixture.configpath`: a properties record with a `Path directory` and a nested list item with a `List<Path> roots`. No application file is edited | `ArchitectureRulesCatchViolationsTest.ruleRejectsItsFixture` (`configpath: UploadProperties.directory`, `configpath: UploadProperties$Mirror.roots`) | both components are reported by name. The clean tree's properties record, which binds its directory as text, passes, and the rule selects it, so it does not pass by checking nothing |
| TDD 5.3: one RUNNING run per source, the partial unique index `reconciliation_runs_one_running_per_source` (V11) | inside a rolled-back transaction, the index dropped | `DatabaseMechanismTest.withoutThisMechanismTheViolationGoesThrough[RECONCILIATION_RUNS_ONE_RUNNING_PER_SOURCE]` | a second RUNNING row for the source is refused by the index by name, then accepted once the index alone is gone. `PartialUniqueIndexesTest` shows what it allows: RUNNING runs of two sources, and finished runs of the same one |
| The same index, as the only thing that refuses a second run of a source through the use case (`RunMatchingTest.twoRunsOnOneSourceOneIsRefused`) | a throwaway database, migrated as usual, then the index dropped by its owner before the context starts. No application file is edited | `OneRunningRunPerSourceBreakProofTest.withoutTheIndexBothRunsStart` | two runs of one source started together are both RUNNING inside their work at once, and both complete. With the index, the second is refused with `SOURCE_BUSY`, caused by `RunAlreadyRunningException`, and leaves no row |
| Grants: recon_app may update only `status`, `stats` and `finished_at` of `reconciliation_runs` (V12, column-level) | the existing proofs: an extra table or column grant made inside a rolled-back transaction; and, for the withheld columns, the missing grant made | `ApplicationRoleGrantsTest.anExtraGrantIsReported`; `WithheldPrivilegeTest.theMissingGrantIsWhatRefusesIt[RECONCILIATION_RUNS_UPDATE_CONFIG_SNAPSHOT]` and `[RECONCILIATION_RUNS_UPDATE_SCOPE]` | the extra grants are reported. Updating the configuration snapshot or the value-date range is refused with 42501, and goes through once that column is granted |

## Phase 5, part 1b

Stage A adds no constraint, index, trigger or grant: its statements use the grants of V8, V9 and
V12 as they are, and `ApplicationRoleGrantsTest` is unchanged. The new mechanisms are the run's
own: what keeps an actively matched item out of a run, what keeps a run from opening a second
unresolved break, and the finalization check on its statistics. Part 1a's two owed proofs are here
too. The one-off proofs were made at `19da3e9`, each a throwaway edit of `JdbcStageAStore` that was
never committed: the file was copied aside first, copied back afterwards, `git diff` was then
empty, and the tests named were run green again (`StageAMatchingTest` and `StageAPropertiesTest`,
30 tests, 0 failures).

### Proven by permanent tests

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| NFR-REL-2: the rollback of the run's work transaction removes everything Stage A wrote (`RunMatching`, TDD 5.3; owed from part 1a) | an application context of its own whose `Transactions` commit the work even when it throws (`@Primary` test bean), with `FailingRunStore` failing the run right after COMPLETED is written, so after Stage A has matched and opened. No file is edited | `WorkRollbackBreakProofTest.withoutTheRollbackTheWorkRemains` | the A1 match and the AMOUNT_MISMATCH break remain and the run stays COMPLETED. With the rollback, `RunMatchingTest.failureAfterStageAWroteLeavesNoneOfIt`: the run is FAILED, neither the match, the break nor its event exists, and a re-run makes both |
| TDD 5.3: a run a stopped instance left RUNNING is set FAILED at startup (`MatchingConfiguration.failRunsLeftRunning`; owed from part 1a) | a throwaway database holding a RUNNING run written before the context starts, and a `BeanDefinitionRegistryPostProcessor` of the test's own context that removes the recovery bean's definition and nothing else | `StaleRunRecoveryBreakProofTest.withoutTheRecoveryTheSourceStaysBusy` | the context lacks the bean, the run is still RUNNING, the source reports it as running, and a new run is refused `SOURCE_BUSY` naming it. With the recovery, `StaleRunRecoveryTest` sees it FAILED and the source free |
| INV-2's index `match_items_active_item_unique` stops a second active match when the run's exclusion cannot see the first | another connection matches the two items and keeps its transaction open while a run decides; the run's statement does not see the uncommitted match | `StageAMatchingTest.indexStopsAMatchTheExclusionCannotSee` | the run waits on the index and, once the other transaction commits, fails with that index's unique violation and is FAILED; the items keep the one match made elsewhere. The index itself is proven by `DatabaseMechanismTest[MATCH_ITEMS_ACTIVE_ITEM_UNIQUE]` (Phase 2) |
| INV-1, INV-4: the run's finalization check (`ItemStatistics.conserved`) | totals by status that do not add up to the scope: an item counted twice, an item missed, amounts that differ by one minor unit, a status in a currency the scope lacks | `ItemStatisticsTest.countThatDoesNotAddUpIsRefused`, `sumThatDoesNotAddUpIsRefused`, `statusOutsideTheScopeIsRefused` | each is refused with `ScopeNotConservedException`, which fails the run inside its work transaction |

### Recorded one-offs

| Mechanism | Broken by | Test | What it reported |
|---|---|---|---|
| FR-MAT-2: each Stage A statement offers only items without an active match (`NOT EXISTS` on `match_items` in the `psp` and `ledger` CTEs of `JdbcStageAStore.UNMATCHED`) | both `NOT EXISTS` conditions replaced by `TRUE` | `StageAMatchingTest#laterRunNeverAltersAnActiveMatch+reRunWithNoNewDataWritesOnlyItsRunRow` | 2 tests, 1 failure, 1 error. `laterRunNeverAltersAnActiveMatch`: `could not find the following elements: [("LEDGER 9700000002", "PSP L-002", …)]`: the matched L-001 was counted again and made L-002 a duplicate reference. `reRunWithNoNewDataWritesOnlyItsRunRow`: the second run failed with `duplicate key value violates unique constraint "match_items_active_item_unique"`. Restored: both pass |
| INV-7, TDD 8.2: a run never opens a second unresolved break and writes nothing for the item instead (`ON CONFLICT (item_side, item_id) WHERE status <> 'RESOLVED' DO NOTHING` in `JdbcStageAStore.BREAK_WRITES`) | the `ON CONFLICT` line deleted | `StageAMatchingTest#reRunWithNoNewDataWritesOnlyItsRunRow+anUnresolvedBreakIsNeverOpenedTwice` | 2 tests, 2 errors, 4 × `duplicate key value violates unique constraint "breaks_one_unresolved_per_item"`: the index refuses the second break and the run fails, where the clause lets it skip the item and complete. Restored: both pass |
| INV-5, FR-MAT-4: no rule breaks a tie, so the INV-5 property must catch one that does | A1 given a tie-break: a line with several exact entries matched the one with the smallest generated id (`(array_agg(ledger_id ORDER BY ledger_id))[1]`, `HAVING bool_or(in_range)`) instead of opening AMBIGUOUS_MATCH | `StageAPropertiesTest` (both properties, seed 20261003) | INV-5 failed at try 8 of 20: `[order 1 against the order generated]`, PSP L-3 matched `LEDGER #6` in the generated order and `LEDGER #7` in the first shuffle. The INV-1/INV-4 property passed its 40 tries, as it should: a tie-break conserves the scope. Restored: both pass |

### Unproven

| Mechanism | Why there is no break proof | What guards it instead |
|---|---|---|
| MATCHED_LATE resolves a break only from the status it read, locked (`FOR UPDATE OF open_break` and `breaks.status = target.status` in `JdbcStageAStore.RESOLVE_MATCHED_LATE`) | nothing else changes a break's status until Phase 7's transition endpoint: ingestion only opens breaks, and runs of one source cannot overlap (V11). The broken state, an operator's transition committed between the run's read and its update, cannot be built yet | `StageAMatchingTest.investigatedBreakIsResolvedWhenItsItemMatches` shows the event records the status the break left; to be proven in Phase 7 against a concurrent transition, alongside the transition endpoint's own guard (TDD 14, Phase 7 exit) |

The INV-1/INV-4 property also checks itself: jqwik's coverage check fails it unless every match
rule and Stage A break type occurs in at least 5 % of its tries. Its first generator, items drawn
independently, failed that check (`Percentage of 3.33 for true does not fulfill condition for
label "A1_EXACT_REFERENCE"`); the committed one generates transactions as both sides see them.

## Phase 5, part 1c

TDD v1.9 settled three points of part 1b's Stage A: MATCHED_LATE resolves only the break types a
match answers, the run's work transaction runs at REPEATABLE READ, and A2 ignores the value-date
window. The new mechanism is the isolation level of the work transaction (`SpringTransactions`,
`Transactions.inSnapshotTransaction`, used by `RunMatching`). No constraint, index, trigger or grant
changed, and `ApplicationRoleGrantsTest` is unchanged.

### Proven by permanent tests

| Mechanism | Broken state the test builds | Proof test | What it asserts |
|---|---|---|---|
| TDD 5.3: every statement of a run reads the snapshot its work transaction took (REPEATABLE READ) | an application context of its own whose `Transactions` runs the snapshot transaction at READ COMMITTED (`@Primary` test bean), with `HeldStageAStore` holding the run after A1, its first statement, while an entry and a line that A1 would pair, both past their grace periods, are committed. No file is edited | `RunSnapshotBreakProofTest.withoutTheSnapshotALaterStatementSeesTheCommit` | A1 left the pair unmatched, and the same run's grace statement opened MISSING_IN_PSP on the entry and MISSING_IN_LEDGER on the line, counting both as broken; the next run matches the pair and resolves both breaks MATCHED_LATE. At REPEATABLE READ, `RunSnapshotTest.itemsCommittedDuringTheRunAreSeenByNoneOfItsStatements`: no match, no break and no count for the pair, and the next run matches it |
| TDD 5.3: a serialization failure fails the run like any other failure | the same hold, while an operator's transition (`BreakStore.apply`, OPEN to INVESTIGATING) is committed on the MISSING_IN_LEDGER break of the line A1 has just matched | `RunSnapshotTest.serializationFailureFailsTheRun` | MATCHED_LATE's `FOR UPDATE` meets a row changed after the snapshot: `could not serialize access due to concurrent update`, the run is FAILED, A1's match is gone, and the break keeps its two operator events. The next run matches the line and resolves the break |

### Recorded one-offs

Each was made on the tree of its commit, by a throwaway edit of `JdbcStageAStore` that was never
committed: the file was copied aside first and copied back afterwards, `git diff` then showed only
the intended change, and the tests named passed again.

| Rule | Broken by | Test | What it reported |
|---|---|---|---|
| FR-BRK-5 as settled in v1.9: MATCHED_LATE resolves only MISSING_IN_PSP, MISSING_IN_LEDGER and AMBIGUOUS_MATCH (`break_type IN (...)` in `RESOLVE_MATCHED_LATE`) | that condition deleted | `StageAMatchingTest#duplicateAndConflictBreaksStayOpenWhenTheirItemMatches` | 1 test, 1 failure, `[as they were opened]`: the matched lines' DUPLICATE_LINE, AMOUNT_MISMATCH and CURRENCY_MISMATCH breaks were resolved. Restored: it passes |
| A2 ignores the window (`REFERENCE_FINDINGS`, v1.9) | the statement as part 1b left it, window applied to every carrier (`git show HEAD:` of the file, before the change was committed) | `StageAMatchingTest#referenceConflictOutsideTheWindowIsStillAConflict+exactReferenceOutsideTheWindowIsNeitherAMatchNorAConflict+conflictingEntriesAreAmbiguousWhateverTheirDates` | 3 tests, 2 failures: `[three business days apart, and an entry two months before its line]` (no A2 breaks, grace breaks instead) and `conflictingEntriesAreAmbiguousWhateverTheirDates` (AMOUNT_MISMATCH naming the near entry only). The exact pair beyond the window behaves the same both ways, as it should: A1 keeps the window. Restored: all three pass |

### Unproven

Part 1b listed MATCHED_LATE's status guard as Unproven because no concurrent transition could be
built. One can now be built in a test, through the store operation Phase 7's endpoint will use, and
at REPEATABLE READ it never reaches the guard: the `FOR UPDATE` fails the run first (above).

| Mechanism | Why there is no break proof | What guards it instead |
|---|---|---|
| `breaks.status = target.status` in `JdbcStageAStore.RESOLVE_MATCHED_LATE` | unreachable at REPEATABLE READ. A transition committed before the snapshot is read as the break's status; one committed after it, or still open when the run locks the row, fails the `FOR UPDATE` with a serialization failure. Within the statement the lock holds the row, so the status cannot change between the read and the update | the snapshot, proven above (`RunSnapshotTest.serializationFailureFailsTheRun`); the condition stays as defence in depth, to be looked at again with Phase 7's transition endpoint |
