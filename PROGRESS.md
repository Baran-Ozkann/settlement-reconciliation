# Progress

**Current phase:** 5 — Stage A matching, parts 1a to 2b built; per-commit verification open in CI;
NFR-PERF-2's 120 s target not met
**Branch:** main (the phase prompt directs the work here rather than onto a phase branch)
**Last updated:** 2026-10-06

## Phase 5 — parts 1a, 1b, 1c, 2a and 2b built; per-commit verification open (CI)

Phase 5 runs in five sessions: 1a (the ArchUnit configuration rule, the schema and the run
lifecycle, no matching rule), 1b (Stage A rules, item statuses, property tests), 1c (three
changes to 1b that TDD v1.9 settled), 2a (A1 as reference identity, the run endpoints, the
automatic trigger) and 2b (NFR-PERF-2, the break-proofs record, per-commit verification, the
phase report).

### Done in 2b

The owner answered the five 2a open questions in TDD v1.11 (`3eefc19`): keep the trigger switch
(1), v1.11 is the text on disk (2), give a busy source up after a bound (3), answer a failed run's
POST with its id (4), and one exact entry beside a conflicting one is an A1 match (5). The commits
call them owner decisions 1 to 4 of part 2b.

- [x] A. The give-up bound. `3e38f61` binds `recon.matching.automatic-trigger.busy-give-up-after`
  (30 min; a duration that is not positive stops startup, `AutomaticTriggerConfigurationTest`).
  `866ca1f`: the trigger measures on the injected `Clock` how long the source has been busy since
  the first refusal; a refusal at or past the bound gives the run up, logs it at WARN with the
  file's id, and the thread goes on to the next file. `RunTriggerGiveUpTest` (a one-second bound: the
  stuck source's run is given up and never recorded, another source's run completes while the first
  is held, the run is then started by hand); `AutomaticRunTriggerTest` pins the boundary on a manual
  clock (refused from 0 s to 1,800 s is given up at the try at 1,800 s; free at 1,800 s is started).
  Break proof `TriggerWithoutGiveUpBreakProofTest` (`c26e056`): with the bound at a day, for three
  seconds the other source gets no run and nothing is given up
- [x] B. `runId` in the 500 (`995cd4a`): `RunMatching` throws `RunFailedException`, carrying the run's
  id with the failure as its cause, once the run is recorded FAILED; the API answers 500 Problem
  Details whose only addition is `runId`. The automatic trigger logs the failed run's id with the
  file's id. `RunApiTest.failedRunIs500WithItsId`, with `LeakCheck`; `FailingRunStore` became public
- [x] C. Decision 4 tested (`6ff6eca`): one exact entry beside a conflicting one is an A1 match, the
  conflicting entry reaches MISSING_IN_PSP through its grace period, no A2 break. Decision 1 pinned
  (`d96fddc`): `StatementUploadTest` sees `RunTrigger.NONE` in the test profile, `RunAfterUploadTest`
  sees the `AutomaticRunTrigger` with the switch on
- [x] D. NFR-PERF-2 test (`acad3b9`, `StageAPerformanceTest`, `-Pperf`, `-Xmx512m`): a database of the
  test's own filled by PostgreSQL with 1,000,000 lines and 1,000,000 entries (900,000 A1 pairs,
  50,000 A3 pairs, 20,000 amount conflicts, 15,000 unknown references beside 15,000 unnamed entries,
  15,000 lines repeating a reference two by two), analysed, then one run timed from the RUNNING row
  to its return. Store proxies time each statement and the commit; peak heap is printed. It asserts
  the outcome counts the data was built for, then 120 s. The load is not timed
- [x] E. Two SQL changes kept, each with `StageAMatchingTest` and `StageAPropertiesTest` unchanged and
  passing: `5b68cbc`, A3's unreferenced lines as two `UNION ALL` branches, so the known-reference
  test is one hash anti-join instead of a `NOT EXISTS` inside an `OR` that scanned the million
  entries once per referenced line; `0a9fba8`, a duplicate reference's other lines gathered by one
  join and aggregation instead of a correlated subquery per duplicate. Same rows either way. No
  index, constraint or grant was added (1b decision 10)
- [ ] F. **The 120 s target is not met** (measurements below). A deferred foreign-key variant (the
  three keys on `match_items` and `match_events` checked at commit) was prepared to be measured, but
  the session's permission check refused the command before it ran: nothing of it was measured,
  and nothing of it is in the repository. It goes to the phase report as a TDD proposal, beside a
  revised target
- [x] G. `docs/break-proofs.md` part 2b and the phase-wide index (`f49e1b8`). `84834ec` reworded the
  perf test's class comment, which the rules script refused (see Verification)
- [x] H. Per-commit verification moved to CI (`43ba4f2`, `.github/workflows/verify-commits.yml`),
  following CLAUDE.md 5 step 4. The local run made before the move is recorded below as a partial
  record; the owner's CI run closes it

### NFR-PERF-2 measurements (2b)

`.\mvnw.cmd -q -B test -Pperf "-Dtest=StageAPerformanceTest"`, one run per row, on the commit named.
Each figure is quoted from that commit's message; the test prints them as `NFR-PERF-2 RESULT` and
`NFR-PERF-2 BREAKDOWN` lines but does not keep them.

| SQL as of | Run | A3 | Reference breaks | Source of the figures |
|---|---|---|---|---|
| `acad3b9` (part 2a's statements) | 3,882.7 s | 3,611.9 s | not quoted | `acad3b9` |
| `5b68cbc` (A3 anti-join) | 274.2 s | 11.3 s | 118 s, 110 s of it the correlated subquery | `5b68cbc`; the 118 s and 110 s are quoted by `0a9fba8` for "the NFR-PERF-2 run" before it |
| `0a9fba8` (duplicate-reference join) | 161.8 s | not quoted | 7.4 s | `0a9fba8` |

**Not in the repository:** the three runs at the final SQL that the phase exit asks for, and the
diagnosis of A1's time (each match writes one row in `matches`, two in `match_items` and one in
`match_events`, with immediate foreign-key checks on the last three). Both were made in the previous
session and kept in its scratch draft of this section, which was not committed and was not searched
for. Their figures are not reproduced here, so as not to quote numbers this record cannot back.
Open question below.

### Decisions in 2b (for the phase report)

1. The busy time is measured from the run's first refusal on the injected `Clock`, and the first try
   at or past the bound gives up; with the 5 s interval a run is given up within one interval of
   30 minutes
2. If recording FAILED fails as well, the run stays RUNNING and the original failure answers as a
   plain 500 without `runId`, as before (part 1a decision 3)
3. The perf test writes its items as the schema's owner, straight in SQL, not through ingestion or
   the ledger projection, and analyses the tables before timing: NFR-PERF-2 is Stage A's time, and
   the load is NFR-PERF-1's and NFR-PERF-3's
4. Per-commit verification runs in CI from `43ba4f2` on: CLAUDE.md 5 step 4 as the owner changed it

### Open questions for the owner (2b)

1. NFR-PERF-2: the last quoted run is 161.8 s against 120 s. Measure the deferred foreign-key variant
   (TDD change), revise the target, or both? The phase report gives the reasoning for each
2. The three official runs and the A1 foreign-key diagnosis are not in the repository: paste them
   from the previous session's draft, or have them measured again at HEAD?
3. `acad3b9` to `f49e1b8` fail `ci/check-rules.sh` (the word pair `84834ec` removed), so their CI jobs
   will be red. They are pushed and are not rewritten: accept them as a known red range?

### Verification (2b)

At `43ba4f2` (the last commit the build reads; this file changes nothing it reads), default order
only, as CLAUDE.md 5 now has it (the reverse order runs in CI):

- `.\mvnw.cmd -q -B clean verify`: exit 0 in 247 s, 1127 tests, 0 failures, 0 errors, 3 skipped (the
  symbolic link cases in `UploadDirectoryTest`, by assumption on Windows). JaCoCo line coverage:
  domain 99.4 %, overall 96.6 %
- `& "C:\Program Files\Git\bin\bash.exe" ci/check-rules.sh`: exit 0

The two SQL commits' messages record that `StageAMatchingTest` and `StageAPropertiesTest` passed on
each; for the other 2b commits this record holds nothing beyond their messages. No 2b commit was
built on its own with the full build locally: that is the CI run's.

**Per-commit verification of Phase 5** (base `1f46fd430151292c76d29bc2d2b680a9a1c46a1a`, "Record
Phase 4.1 outcomes and settle the Stage A design"; range `1f46fd4..` this commit) moved to CI and
is open until the owner's run of `verify-commits.yml` with that base. Before the move, a local run
(clean verify, or the rules alone for a docs-only commit, then `ci/check-rules.sh`, one commit at a
time) covered 8 rows of the 64 commits before `43ba4f2`, copied here from
`target/verify-results-5.txt` as a partial, local record:

```
1418bbf | Forbid Path components in configuration properties types | exit=0 tests=970 failures=0 errors=0 skipped=3 (180 s) | rules exit=0 | 2026-10-05 23:10:41
2e2eb06 | Finish a reconciliation run as completed or failed in the domain | exit=0 tests=973 failures=0 errors=0 skipped=3 (168 s) | rules exit=0 | 2026-10-05 23:13:56
dc32964 | Record the store tests' parent run as finished, not running | exit=0 tests=973 failures=0 errors=0 skipped=3 (170 s) | rules exit=0 | 2026-10-05 23:16:59
50889c3 | Allow one running reconciliation run per source with a partial index | exit=0 tests=977 failures=0 errors=0 skipped=3 (172 s) | rules exit=0 | 2026-10-05 23:20:05
f082162 | Grant the application the outcome columns of reconciliation runs | exit=0 tests=981 failures=0 errors=0 skipped=3 (196 s) | rules exit=0 | 2026-10-05 23:23:13
f2f61ae | Give the run store the operations of the run lifecycle | exit=0 tests=984 failures=0 errors=0 skipped=3 (195 s) | rules exit=0 | 2026-10-05 23:26:46
3de38b7 | Count a source's ledger entries in run scope and without a value date | exit=0 tests=985 failures=0 errors=0 skipped=3 (196 s) | rules exit=0 | 2026-10-05 23:30:18
14bc542 | Run matching for a source in its own run record and work transaction | exit=127 tests=148 failures=0 errors=0 skipped=0 (179 s) | rules exit=1 | 2026-10-05 23:33:54
```

Seven rows are complete and passed. The eighth, `14bc542`, is not a result: 148 tests is a build cut
short, exit 127 is the shell's "command not found", and the run was stopped there. A `git grep` at
`14bc542` for the two text rules most likely to fire (the attribution words and TODO/FIXME) finds
nothing; CI gives the real result. The file under target/ was copied to the session scratchpad
before this session's clean build deleted it.

### Done in 2a

- [x] A. Owner decision 1 (`a927608`): references are identity. A1 matches an exact pair whatever
  the value dates; several exact entries with the line's reference are AMBIGUOUS_MATCH whatever
  their dates; only A3 uses the window. `StageAMatchingTest`: pairs three business days and two
  months apart are A1 matches with no grace break (stats all MATCHED); two exact entries, one beyond
  the window, are AMBIGUOUS_MATCH naming both; the window and holiday tests now use A3 lines (no
  reference), which still match within the window and not beyond it. `StageAPropertiesTest` passes
  unchanged (seed 20261003, coverage check included): no generator changed, since the properties
  assert invariants, not outcomes, and every outcome they require still occurs
- [x] B. Role rules (`4a6a981`, `RunRolesTest`), then `GET /api/v1/runs/{id}` (`4c8b56c`) and
  `POST /api/v1/runs` (`6ffe304`). GET (VIEWER): 200 with source, range, status, config snapshot,
  stats (none while RUNNING or FAILED), start and finish, trigger; 404 for an unknown id, 400 for a
  malformed one. POST (OPERATOR, JSON `source`, `valueDateFrom`, `valueDateTo`): synchronous, 201
  with Location and the run; 409 with `runningRunId` when the index refuses the RUNNING row; 400 for
  an unknown or malformed source, a reversed range, a missing or non-ISO date (parsed strictly, so
  2026-02-30 is refused) and a body that is not JSON. `RunApiTest` covers every status, 401, 403,
  the cross-site rule, LeakCheck on every error body, and that no refused request records a run
- [x] C. Configuration first (`4594dca`): `recon.matching.automatic-trigger.enabled` (true; false in
  the test profile, decision 1 below), `queue-capacity` (100), `busy-retry-interval` (5 s); an
  impossible value stops startup (`AutomaticTriggerConfigurationTest`). The trigger (`5e45e7c`):
  `IngestStatement` hands a `RunTrigger` port the file's id, source and the earliest and latest value
  dates of the lines it stored (`LineCollector` keeps them per batch, repeats excluded), after the
  INGESTED file has committed. `AutomaticRunTrigger`: one daemon thread, a bounded queue
  (`AbortPolicy`), runs in file order as `system`; on SOURCE_BUSY it logs once that it waits and
  retries every interval; a run it cannot start, whose work fails, or still queued at shutdown is
  WARNed with the file id; a full queue is WARNed with the file id and the upload answered as usual.
  Tests: `AutomaticRunTriggerTest` (8, no Spring), `IngestionRunTriggerTest` (6: the range, bank
  files, repeats, nothing for a file that stored no line, a rejected file or a refused upload),
  `RunAfterUploadTest` (6, over HTTP with a held run: a COMPLETED run for the source and range, PSP
  and bank; none for a rejected file; a wait for a held run, its start no earlier than the other's
  finish; a full queue's WARN while the queued runs still complete; a response that arrives while
  its run is held)
- [x] D. Break proofs, permanent: `TriggerWithoutWaitBreakProofTest` (`23c9c7c`, a trigger that does
  not wait has its run refused and WARNed, never run) and `BlockingTriggerQueueBreakProofTest`
  (`4f21985`, a caller-runs queue holds the upload until the run has run). Recorded one-offs: A1 with
  the window back, A3 without it, the POST role rule deleted. Unproven: the GET role rule (every
  user is a VIEWER until Phase 9). All in `docs/break-proofs.md` part 2a (`4958686`)
- [x] E. Verification below

### Decisions in 2a (for the phase report)

1. **Deviation:** `recon.matching.automatic-trigger.enabled` is a switch the TDD does not ask for.
   The test profile turns the trigger off: test classes share sources and one database, most of
   their files repeat one reference, and a run started behind a test's back would race the runs the
   test starts and open breaks it does not expect. The alternative is the application's trigger
   replaced by a test bean in every class that ingests, which a new class could forget. Open
   question 1
2. The automatic run's range is the earliest and latest value date among the lines the file stored.
   A line skipped as a repeat of another file's is not counted, and a file that stored no line
   triggers no run (logged nowhere: it has nothing new to match)
3. Automatic runs are recorded as triggered by `system`; the uploader is in the file's
   `uploaded_by`, and the trigger's log lines name the file id
4. A bank file triggers a run too (FR-MAT-1 names no type); until Phase 6 it only counts its scope
5. A run whose work fails answers POST with 500, like any failure of the application; the FAILED
   run is readable through GET but its id is not in the 500 body. Open question 4
6. "Several entries carrying a line's reference are ambiguous" is read as several *exact* entries
   (A1's candidates), as `twoExactEntriesAreAmbiguous` already had it: a line with one exact entry
   and one conflicting entry is still matched to the exact one by A1. Open question 5
7. The trigger checks nothing itself before starting a run: the running-run index refuses a busy
   source, and the trigger waits on that refusal. Each retry is one refused insert, which
   PostgreSQL logs on its side; 5 s keeps that to 24 a run of the 120 s target
8. While a source stays busy the trigger waits without limit, holding its one thread. A run left
   RUNNING by an `Error` (part 1a decision 3) would hold every later triggered run until restart,
   when startup recovery frees the source. Known risk, open question 3
9. `HeldLedgerEntryStore` became public, so the web tests can hold a run; `StatementFiles` gained a
   PSP line and a bank line on a given value date (a shared fixture: the full build ran before the
   commit)

### Open questions for the owner (2a)

1. Keep `recon.matching.automatic-trigger.enabled` (off in the test profile), or replace it with a
   test bean in each class that ingests?
2. The TDD on disk is v1.9: no v1.10 is committed. Decision 1 was built as the prompt states it.
   §8.2's A1 row still says "value dates within window", and its Stage A details say "A1 and A3
   keep the window"; both need the v1.10 text
3. Should the trigger give up on a source busy for longer than some bound (WARN with the file id),
   so one stuck run cannot hold every later triggered run?
4. Should a POST whose run failed answer with the FAILED run's id (500 or another status)?
5. Decision 6: confirm that one exact entry plus a conflicting one under the same reference is an A1
   match, not AMBIGUOUS_MATCH

### Verification (2a)

At `4958686` (the last code and docs commit of 2a; this file changes nothing the build reads):

- `.\mvnw.cmd -q -B clean verify`: exit 0, 1116 tests, 0 failures, 0 errors, 3 skipped (the
  symbolic link cases in `UploadDirectoryTest`, by assumption on Windows). JaCoCo line coverage:
  domain 99.4 %, overall 96.7 %
- `.\mvnw.cmd -q -B clean verify -Dsurefire.runOrder=reversealphabetical`: exit 0, the same
  1116 / 0 / 0 / 3, and the same coverage
- `& "C:\Program Files\Git\bin\bash.exe" ci/check-rules.sh`: exit 0

Each 2a commit passed the tests it touches before it was committed; `5e45e7c` also passed the full
`clean verify` (1114 / 0 / 0 / 3) because it changes a shared fixture. Its first full build failed
once, before the commit: `IngestionRunTriggerTest` ingested a header-only file, whose content (and
hash) another class had already ingested in the shared database (409). That case was dropped, since
a header-only file cannot carry content of its own; the all-repeated file still covers "stored no
line". The full build was not run per commit for the other 2a commits (left for 2b, as for part 1).

### Done in 1a

- [x] A. TDD 11.1: `ArchitectureRules.noPathBoundFromConfiguration`. No `@ConfigurationProperties`
  type, nor a type nested in one, has a `Path` component, directly or inside a generic type. The
  fixture `com.baran.archfixture.configpath` breaks it twice; the clean tree gains a text-bound record
- [x] B. V11: partial unique index `reconciliation_runs_one_running_per_source` (`DatabaseMechanism`
  entry; `PartialUniqueIndexesTest` shows what it allows). V12: `UPDATE (status, stats,
  finished_at)` on `reconciliation_runs`, column-level, pinned in `ApplicationRoleGrantsTest`. The
  snapshot and the range stay unwritable (`WithheldPrivilege`)
- [x] C. `application.run.RunMatching`: the RUNNING row with its snapshot commits alone. A second
  run of the source is refused there as `RunRefusedException(SOURCE_BUSY)`, naming the running run.
  The work transaction holds Stage A's place (a PSP source only; it does nothing yet), the
  statistics and COMPLETED. On failure it is rolled back and FAILED is set in a transaction of its
  own. Also refused before anything is recorded: `UNKNOWN_SOURCE` and `INVALID_DATE_RANGE`. At
  startup a `SmartInitializingSingleton` sets every RUNNING run FAILED, before the web server and
  the listener start, logging run ids only
- [x] Snapshot (FR-MAT-8) in 1a: `value_date_zone`. Stats: `ledger_entries_in_scope` and
  `ledger_entries_without_value_date` (FR-MAT-9/10), both counted in the work transaction with
  bound parameters
- [x] D. `RunMatchingTest`: completes with snapshot and stats; FR-MAT-9/10; a failure right after
  COMPLETED is written leaves only a FAILED row with no stats; two runs of one source started
  together, one refused by the index (no row left); two sources side by side; a bank source; the
  refusals. `StaleRunRecoveryTest` (throwaway database, leftover run written before the context
  starts). `RunStoreTest` and `LedgerEntryStoreTest` for the new store operations
- [x] E. `OneRunningRunPerSourceBreakProofTest`: on a throwaway database without the index, both
  runs start and complete. Rows in `docs/break-proofs.md`

Verification: at `a5a3c4a`, `.\mvnw.cmd -q -B clean verify` gave 994 tests, 0 failures, 0 errors,
3 skipped (the symbolic link cases), with coverage at domain 99.4 % and overall 96.2 %;
`ci/check-rules.sh` exited 0. Commits were not verified one by one with the full build (the prompt
deferred that to part 2). `BreakStoreTest` and `MatchStoreTest` used to leave a RUNNING
`PSP_ALPHA` run behind before each test, which the index refuses the second time. With the
owner's permission the unpushed commits were rebuilt so their fix, `dc32964`, comes just before
the index (`50889c3`); it needs `ReconciliationRun.complete`, so the domain commit `2e2eb06` moved
ahead of it too. The tree is unchanged by the rebuild. `BreakStoreTest`, `MatchStoreTest`,
`PartialUniqueIndexesTest` and `RunStoreTest` pass at every rebuilt commit

### Decisions in 1a (for the phase report)

1. The snapshot holds only `value_date_zone` for now. Windows, grace periods, the business
   calendar and rule versions join it in 1b, with the configuration binding and the rules that
   read them. Today `SourcesProperties` binds none of them.
2. `ledger_entries_in_scope` is a statistic the TDD does not name. It is the run's scope on the
   ledger side, and it is what makes "out of scope" observable before any rule exists. INV-1's
   finalization check will compare against it.
3. A failure is caught as `RuntimeException` at the run's boundary, recorded as FAILED, and
   rethrown. An `Error` (OutOfMemoryError, for example), or a failure to record FAILED, leaves the
   run RUNNING: its source stays busy until the next startup.
4. Startup sets a leftover run's `finished_at` to `GREATEST(now, started_at)`, so a JVM whose clock
   ran ahead cannot trip `reconciliation_runs_finished_after_start` and stop startup.
5. `sources_state` (TDD 10) is not used, and recon_app has no grant on it. Nothing in the TDD
   reads it. Settled by the owner: see Carried below.

### Done in 1b

- [x] A. `RunMatchingTest` asserts each fixture insert wrote a row (`ef4fefe`)
- [x] B. No grant commit: every statement Stage A issues is covered by V5-V9 and V12 as they are
  (INSERT on `matches`, `match_items`, `match_events`, `breaks`, `break_events` and the two
  sequences; `UPDATE (status, resolution_code, resolved_at)` on `breaks`, which also allows its
  `FOR UPDATE`; SELECT on `ledger_entries` and `psp_lines`). Every Stage A test runs as recon_app
  and none met 42501. `ApplicationRoleGrantsTest` is unchanged and still pins the full set
- [x] Configuration: a PSP source binds `value-date-window-days`, `grace-days-ledger-unmatched` and
  `grace-days-psp-unmatched` as `StageASettings` (TDD 8.1's 2, 3, 1 for a key left out; negative,
  or set on a bank source, stops startup); `recon.business-calendar` binds the weekend and the
  holidays (text, parsed as ISO dates). Snapshot (decision 9): `business_calendar_weekend`,
  `business_calendar_holidays`, `value_date_window_days`, the two `grace_days_*` and
  `rule_version.<rule>` for A1, A2 and A3 (all 1, `StageARule`), on a PSP source's run
- [x] Domain: `BusinessCalendar.firstDayWithinGrace`, `BusinessDayIndex` (dates numbered by business
  days, so a window is one subtraction), `ItemStatus`, `ItemTotal`, `ItemStatistics` (the
  finalization check) and `ScopeNotConservedException`, each with unit and jqwik tests
- [x] C. Stage A in `JdbcStageAStore` behind the `StageAStore` port, run by `RunMatching` inside the
  work transaction: A1, the reference breaks (A2, duplicate references, A1 ambiguity), A3 with its
  ambiguity, MATCHED_LATE, the grace breaks. Every match records rule id, rule version, run id,
  cardinality, zero difference with its currency, and low_confidence (FR-MAT-6)
- [x] D. Stats per side and currency: `ledger.TRY.matched.count`, `psp.EUR.broken.sum` and so on,
  every status present; PSP lines count their gross amount
- [x] E. `StageAMatchingTest` (28 tests: every rule outcome and every TDD 8.2 Stage A detail, INV-7,
  MATCHED_LATE from OPEN and INVESTIGATING with its event, the incremental and re-run proofs, the
  index backstop); `StageAPropertiesTest` (jqwik, seed 20261003: INV-1 and INV-4 over 40 tries
  with two runs each, INV-5 over 20 tries in three insertion orders each, compared by line id and
  event id; a coverage check requires every match rule and break type in at least 5 % of tries)
- [x] F. Break proofs in `docs/break-proofs.md` (part 1b): the two owed from 1a
  (`WorkRollbackBreakProofTest`, `StaleRunRecoveryBreakProofTest`), the index behind the exclusion,
  the finalization check, and three recorded one-offs (exclusion, `ON CONFLICT`, INV-5 against a
  tie-break). One Unproven entry: MATCHED_LATE's status guard, until Phase 7 gives it a concurrent
  writer
- [x] G. The full suite in default and in reverse-alphabetical order (below)

### The matching SQL, in short

Six statements in the work transaction, in rule order, each a `WITH` query over the source's rows:
CTEs pick the unmatched items (`NOT EXISTS` an active `match_items` row), decide, and
data-modifying CTEs write the matches with their items and events, or the breaks with their
events. Nothing is read into the JVM but counts. 1) A1: lines whose reference (read as a UUID by
`pg_input_is_valid` and a cast) no other unmatched line carries, joined to unmatched entries by
transaction id, currency and amount within the window; a pair is kept when the line has exactly
one candidate (`HAVING count(*) = 1`), counted over every date before the range applies. 2) The
reference breaks for lines in the range. 3) A3 and its ambiguity breaks from one snapshot, unique
both ways. 4) MATCHED_LATE: the run's matched items' unresolved breaks, locked, resolved with their
events. 5) The grace breaks. 6) The totals by status and in scope, which `ItemStatistics` checks.
The window is `abs(ordinal - ordinal) <= :windowDays` over the run's `BusinessDayIndex`, bound as
two arrays; breaks are inserted with `ON CONFLICT` on INV-7's index, so a re-run writes nothing.
No `ORDER BY ... LIMIT` decides anything: `(array_agg(id))[1]` appears only where the count is 1.

### Decisions in 1b (for the phase report)

1. A PSP source that leaves a Stage A key out takes TDD 8.1's value; the snapshot records the value
   used. A missing `recon.business-calendar.weekend` is Saturday and Sunday
2. Every pair a rule looks at must be within the window, A2's included (TDD 8.2: a candidate lies
   within the window of the item). An entry with the line's reference outside the window gives no
   A2 break; both items wait for their grace breaks
3. When several unmatched entries carry the line's reference within the window and none has its
   currency and amount, the line gets AMBIGUOUS_MATCH naming them all, not A2: nothing says which
   one it conflicts with
4. A pair is matched when either item is in the run's range; breaks are opened only on items in
   the range. A duplicate-reference break names the other lines, in the range or not, so those are
   BROKEN until a run covering them opens their own
5. Duplicate references are counted among unmatched lines with a UUID reference. A line whose
   reference is repeated, known or not, gets DUPLICATE_LINE and is not offered to A3
6. A3 is unique both ways: the line has one candidate, and no other A3 line within reach has that
   entry as a candidate. Otherwise every such line in the range gets AMBIGUOUS_MATCH naming its
   candidates. A3's candidates are any unmatched dated entries, including one whose transaction id
   another line references (TDD 8.2 as written)
7. A reference is known when any entry of the source carries it, five-field history included
8. The finalization check fails the run when its statuses do not add up to its scope
9. `RunMatching` logs ids and counts through `java.lang.System.Logger`, since the application layer
   depends on java.* alone (TDD 5.2); Spring Boot routes it to the application's log
10. Indexes for Stage A (for example on `ledger_entries (source_code, transaction_id)`) are left to
    part 2, to be added only where the NFR-PERF-2 measurement shows a need

### Open questions for the owner (1b)

Settled by the owner in TDD v1.9 (`137ded2`) and built in 1c: 1 (MATCHED_LATE limited to the types a
match answers), 3 (REPEATABLE READ) and 4 (A2 whatever the dates). 2 is OQ-6, which waits for
Phase 7.

1. Decision 6 of the prompt resolves every unresolved break of a matched item as MATCHED_LATE. That
   includes a DUPLICATE_LINE break ingestion opened on a stored line because a later file repeated
   its line id: matching the stored line then closes the duplicate as MATCHED_LATE, though the
   repeated line is still unexplained. Built as written; should MATCHED_LATE be limited to the
   break types a match answers (MISSING_IN_*, AMBIGUOUS_MATCH, the A2 types)?
2. Only an unresolved break stops a run from opening one (INV-7). Once Phase 7 lets an operator
   resolve a break (WRITTEN_OFF, say), the next run of the range opens the same break again on the
   still unmatched item. Should a run skip an item whose break of the same type was resolved by an
   operator?
3. The work transaction is READ COMMITTED, so each Stage A statement sees its own snapshot. A file
   committed while a run is between statements can have its lines past grace given
   MISSING_IN_LEDGER before A1 has seen them; the next run matches them and resolves the break as
   MATCHED_LATE. The finalization check is not affected, since the totals are one statement. Should
   the work transaction be REPEATABLE READ, so a run decides on one snapshot (a concurrent change
   to a break it resolves would then fail the run with a serialization error)?
4. A2 outside the window (decision 2 above): should a same-reference conflict be reported as A2
   whatever the dates?

### Verification (1b)

This heading was committed empty in `b558a22`, and the 1b numbers were not recorded anywhere else.
Section G above says the suite ran in both orders; the 1c verification below covers the tree with
1b and 1c together.

### Done in 1c

- [x] A. MATCHED_LATE resolves only MISSING_IN_PSP, MISSING_IN_LEDGER and AMBIGUOUS_MATCH
  (`5fc5ea4`). `StageAMatchingTest.duplicateAndConflictBreaksStayOpenWhenTheirItemMatches`: a line
  with ingestion's DUPLICATE_LINE (through `IngestStatement`, a second file repeating its line id)
  and lines with run-opened AMOUNT_MISMATCH and CURRENCY_MISMATCH are matched, their breaks stay as
  opened with one event each, and the stats count them MATCHED (the entries the A2 breaks name stay
  BROKEN). `missingItemBreaksAreResolvedWhenTheirItemsMatch`: run-opened MISSING_IN_PSP and
  MISSING_IN_LEDGER are resolved MATCHED_LATE with their events; AMBIGUOUS_MATCH and the
  INVESTIGATING case were already covered
- [x] B. The work transaction runs at REPEATABLE READ (`814869c`): `Transactions` gains
  `inSnapshotTransaction`, which `SpringTransactions` runs at that level, and `RunMatching` uses it
  for the work alone (RUNNING and FAILED keep their own READ COMMITTED transactions).
  `SpringTransactionsTest` reads `transaction_isolation` in both. `HeldStageAStore` holds a run right
  after A1, its first statement. `RunSnapshotTest`: an entry and a line committed during the hold get
  no match, no grace break and no count, and the next run matches them; an operator's transition
  committed during the hold on a break the run would resolve fails the run with `could not
  serialize access due to concurrent update` (FAILED, A1's match gone), and the next run resolves
  it. The three test doubles of `Transactions` gained the method: the two commit-despite-failure
  beans apply it to both methods, and the ledger projection's fake refuses it
- [x] B, break proof (`46fdaf7`): `RunSnapshotBreakProofTest`, a context of its own whose snapshot
  transaction is READ COMMITTED: A1 misses the pair and the same run's grace statement opens
  MISSING_IN_PSP and MISSING_IN_LEDGER on it. Built, so nothing Unproven for the snapshot
- [x] C. A2 ignores the window (`c662cd3`). Tests: another amount and another currency three
  business days apart, and another amount two months apart, each give their A2 break and no grace
  break; an exact pair beyond the window is neither a match nor a conflict and both items get
  their grace breaks; two conflicting entries, one beyond the window, give AMBIGUOUS_MATCH naming
  both. Inside the window the existing A2 and ambiguity tests are unchanged and pass
- [x] D. `StageAPropertiesTest` passes unchanged after each of A, B and C (seed 20261003, coverage
  check included). No generator changed: the properties assert invariants, not outcomes, and every
  outcome they require still occurs
- [x] E. `docs/break-proofs.md` part 1c (`9f8713c`), and this file
- [x] F. Verification below

### Decisions in 1c (for the phase report)

1. The snapshot is a second method on the `Transactions` port rather than an isolation argument:
   the run's work is the only caller, and the name says what it guarantees
2. A2's conflicting entries count whatever their dates, and so does its ambiguity: two entries
   with the line's reference that both conflict give AMBIGUOUS_MATCH naming both, even when only
   one is within the window (1b decision 3 extended, since the dates no longer say which one the
   line conflicts with). A1's ambiguity keeps the window: several exact entries within it
3. An entry with the line's reference, currency and amount beyond the window is neither an A1
   candidate nor an A2 conflict, as v1.9 reads ("A1 and A3 keep the window"). The line and the
   entry each reach their grace break. With a conflicting entry as well, the line gets that
   entry's A2 break
4. 1b decision 2 (every pair a rule looks at is within the window, A2's included) is replaced by 2
   and 3 above
5. The 1b Unproven entry for MATCHED_LATE's status guard is restated: the concurrent transition
   can now be built in a test, and at REPEATABLE READ it fails the run before the guard is reached

### Verification (1c)

At `9f8713c` (the last code and docs commit of 1c; this file changes nothing the build reads):

- `.\mvnw.cmd -q -B clean verify`: exit 0, 1062 tests, 0 failures, 0 errors, 3 skipped (the
  symbolic link cases in `UploadDirectoryTest`, by assumption on Windows). JaCoCo line coverage:
  domain 99.4 %, overall 96.7 %
- `.\mvnw.cmd -q -B clean verify -Dsurefire.runOrder=reversealphabetical`: exit 0, the same
  1062 / 0 / 0 / 3, and the same coverage
- `& "C:\Program Files\Git\bin\bash.exe" ci/check-rules.sh`: exit 0

Each 1c commit also passed the tests it touches before it was committed (`StageAMatchingTest`,
`StageAPropertiesTest`, `RunMatchingTest`, `WorkRollbackBreakProofTest` and, from B on,
`RunSnapshotTest`, `RunSnapshotBreakProofTest`, `SpringTransactionsTest`,
`FailureAfterFileRowBreakProofTest`, `ProjectLedgerEventsTest`); the full build was not run per
commit (left for part 2, as for 1a and 1b). Docker Desktop was not running at the start of the
session and was started for Testcontainers; no setting was changed

### Left for 2b (done in 2b, above, except the target and the per-commit run in CI)

- NFR-PERF-2 measured three times under `-Xmx512m`, with indexes added only if the measurement
  calls for them; each match costs one row in `matches`, two in `match_items` and one in
  `match_events`, with immediate foreign-key checks on the last three. A1 no longer joins the
  business-day index, so its plan changes from the one part 1 had
- `docs/break-proofs.md`: the record for the phase as a whole (parts 1a-2a are each in it)
- Per-commit verification of the part 1a, 1b, 1c and 2a commits; the phase report

### Carried

- `sources_state` stays unused and ungranted until Phase 7. If nothing reads it by then, a
  migration drops it (owner's decision, 2026-10-03)

### Notes for the next sessions

- Every new application context runs the startup recovery against its database. In the shared
  test database this sets FAILED any run another class left RUNNING, so a test that holds a run
  must do so within one context. Store tests that only need a run to refer to record it finished
- A context that needs a database prepared before it starts uses `ReconPostgres.throwaway()`,
  `registerIn(registry)` in its `@DynamicPropertySource`, and `@DirtiesContext`
- `HeldLedgerEntryStore` holds a run inside its work transaction, and `FailingRunStore` fails a run
  right after COMPLETED is written (both in `application.run`, test sources)
- Stage A tests: `StageAFixture` stores items through the application's stores and reads results
  back by line id and event id; `FixedRunClock` fixes runs at Monday 2026-10-12. Keys taken in
  1b: event ids 9_700_000_000 (`StageAMatchingTest`), 9_750_000_000 (`StageAPropertiesTest`, a
  block of 1,000 per source), 9_800_000_000 (`WorkRollbackBreakProofTest`), 9_600_500_000
  (`RunMatchingTest`'s fixture); sources `PSP_STAGE_A_*`, `PSP_PROPERTY_*`, `PSP_RUN_ROLLBACK`,
  `PSP_RUN_NO_ROLLBACK`, `PSP_STALE_KEPT`. Taken in 1c: 9_850_000_000 (`RunSnapshotTest`, a block of
  1,000 per test) and 9_870_000_000 (`RunSnapshotBreakProofTest`); sources `PSP_SNAPSHOT_UNSEEN`,
  `PSP_SNAPSHOT_SERIAL`, `PSP_SNAPSHOT_READ_COMMITTED`. `StageAMatchingTest` uses 35 of its 40
  sources. Taken in 2a (sources only, no event ids): `PSP_RUN_API` (`RunApiTest`; `PSP_ROLES` is
  only named in `RunRolesTest`'s requests), `PSP_TRIGGER_RANGE`, `BANK_TRIGGER_RANGE`,
  `PSP_AFTER_UPLOAD_*`, `BANK_AFTER_UPLOAD_DONE`, `PSP_TRIGGER_NO_WAIT`, `PSP_TRIGGER_CALLER_RUNS`.
  Taken in 2b: `PSP_GIVE_UP_STUCK`, `PSP_GIVE_UP_NEXT`, `PSP_NO_GIVE_UP_STUCK`, `PSP_NO_GIVE_UP_NEXT`,
  `PSP_RUN_API_FAILS`, and `PSP_STAGE_A_PERF` with event ids from 20_000_000_000 (a database of its
  own)
- Per-commit verification runs in CI (`verify-commits.yml`): a push to main verifies the commits it
  adds; a run by hand with a base verifies `base..main`. Locally, one full `clean verify` and the
  rules script at a phase's last code commit
- The automatic trigger is off in the test profile. A class that tests it sets
  `recon.matching.automatic-trigger.enabled=true` in its own properties, with a short
  `busy-retry-interval`; `HeldLedgerEntryStore` (now public) holds a run inside its work for any
  source type
- Every header-only file has the same hash, so only one can ever be ingested in the shared test
  database
- `HeldStageAStore.runHeldWhile` holds a run after A1 while a test commits something, then lets it
  finish; it holds inside the work transaction after the snapshot was taken
- jqwik runs `StageAPropertiesTest`; a `TestContextManager` prepares its Spring context. A new
  jqwik class needing the application can do the same
- A mistake in the 1a session: two build logs were written to the system temp directory (`/tmp`
  in Git Bash) and moved under target/ at once. No repository content was in them beyond test
  output, and they never reached a commit

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
