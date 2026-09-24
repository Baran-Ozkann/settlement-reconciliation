# Ledger integration notes

What `settlement-reconciliation` may assume about `ledger-payment-core`, taken from its source
rather than from its README or from this project's TDD.

**Method.** `..\ledger-payment-core` was read as a working tree on 2026-09-24. It was not built, not
tested, and no git command was run in it, so the evidence below is a path and a line number in that
working tree rather than a commit sha. Every path is relative to `../ledger-payment-core/`. Where the
ledger's own README disagrees with its code, the code is taken as the fact and the disagreement is
recorded in [TDD corrections](#tdd-corrections).

**Revision 2026-09-24, after the ledger change.** The Phase 0 citations match ledger commit `b32a5bf`,
the tip of its `main` at the time. The ledger has since added `entry_id` and `created_at` to the
event in commit `e3119e9`, merged to its `main` as `93eadc2`. Evidence for the change is cited
**at `93eadc2`** and marked that way; it was read with `git show` and `git log` only. Nothing in the
ledger was built, run or written. Line numbers not marked `@93eadc2` are still `b32a5bf`'s, and some
of them have moved in the newer tree.

---

## 1. Build, stack and conventions

| Fact | Evidence |
|---|---|
| Maven with the wrapper, Maven 3.9.9 | `.mvn/wrapper/maven-wrapper.properties:1`, `mvnw`, `mvnw.cmd` |
| Spring Boot **4.1.1** | `pom.xml:8-11` |
| Java **21** | `pom.xml:21` |
| Group `com.baran`, artifact `ledger`, base package `com.baran.ledger` | `pom.xml:14-16`, `src/main/java/com/baran/ledger/LedgerApplication.java` |
| No Lombok | no dependency in `pom.xml:29-121`; records are used instead (`domain/AccountActivityEvent.java:12`) |
| No JPA, no Hibernate: `spring-boot-starter-jdbc` only | `pom.xml:35-37`; no `data-jpa` dependency in `pom.xml:29-121` |
| Explicit SQL through `JdbcClient` | `store/AccountRepository.java:32-38`, `store/EntryRepository.java:38-42` |
| Flyway, versioned migrations `V1`...`V12`, never edited in place | `pom.xml:64-75`, `src/main/resources/db/migration/` |
| Jackson 3 (`tools.jackson`), as shipped by Spring Boot 4 | `service/LedgerService.java:11`, `projection/AccountActivityProjection.java:10` |
| Test tagging splits broker-bound tests from the rest | `pom.xml:129-136`, `src/test/java/com/baran/ledger/AbstractKafkaIntegrationTest.java:40` |
| Testcontainers with a real PostgreSQL and a real Kafka | `pom.xml:95-109`, `AbstractKafkaIntegrationTest.java:47-52` |
| jqwik 1.9.3 for property tests | `pom.xml:110-115` |
| No `LICENSE` file in the repository | absent from the repository root |

The Java package is laid out as `api`, `config`, `domain`, `outbox`, `projection`, `recon`,
`retention`, `service`, `store` — flat and role-named, not hexagonal. This service's TDD §5.2 chooses
a different structure on purpose; nothing about the ledger constrains it.

## 2. Money

| Fact | Evidence |
|---|---|
| `long` minor units (kurus), wrapped in a `Money` record | `domain/Money.java:7` |
| Single currency `TRY`, as a constant | `domain/Money.java:13`; written on every account at `store/AccountRepository.java:36` |
| Maximum single amount 10 000 000 000 | `domain/Money.java:10`, mirrored by the `amount_bounded` CHECK in `src/main/resources/db/migration/V2__ledger_core.sql:44` |
| An entry amount is never zero | `V2__ledger_core.sql:43` |
| Amount arithmetic uses `Math.addExact` / `Math.negateExact` | `domain/Money.java:20`, `:24`; `service/LedgerService.java:149` |
| Decimal amounts are rejected at deserialization | `src/main/resources/application.yml:29-31` |
| `BIGINT` over `NUMERIC`/`BigDecimal`, with the reasoning | `docs/adr/007-bigint-minor-units.md` |

This service adopts the same representation (TDD §6). Nothing here contradicts it.

## 3. Accounts

| Fact | Evidence |
|---|---|
| `accounts` carries an internal `BIGSERIAL id` and a `public_id UUID` | `V2__ledger_core.sql:5-6` |
| Only the `public_id` is exposed over the API and on the wire | `domain/Account.java:8`, `service/LedgerService.java:245-249` |
| Account types: `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE` | `domain/AccountType.java:4-10`, `V2__ledger_core.sql:14-15` |
| `allow_negative` is generated from the type: only `EQUITY` may go negative | `V6__derived_allow_negative.sql:12-13` |
| `currency CHAR(3)`, always `TRY` on insert | `V2__ledger_core.sql:9`, `store/AccountRepository.java:33-37` |
| Balance is materialized and moved by a conditional `UPDATE` | `store/AccountRepository.java:63-69` |
| There is no "clearing account", no account tagging, no source attribute; `owner_ref` is free text | `V2__ledger_core.sql:8`, `api/CreateAccountRequest.java:6` |
| Accounts are created through `POST /v1/accounts` with `X-Client-Id` and `Idempotency-Key` | `api/AccountController.java:23`, `:35-45` |

**Consequence for this service.** A ledger account can only be mapped to a source by its `public_id`
UUID, because that is the only account attribute the event carries (§5). TDD §8.1's
`ledger-accounts: [ "<clearing account id from Phase 0>" ]` is therefore a list of account public ids,
and there is no id to fill in yet: no such account exists in any ledger instance we control.

## 4. Transactions and entries

| Fact | Evidence |
|---|---|
| `ledger_transactions` has `public_id UUID`, `tx_type`, optional `reverses_id` | `V2__ledger_core.sql:18-29` |
| The `valid_tx_type` CHECK allows five values: `TRANSFER`, `REVERSAL`, `FUNDING`, `FEE`, `ADJUSTMENT` | `V2__ledger_core.sql:25-26` |
| The Java enum carries only three; only these can be written today | `domain/TxType.java:7-11` |
| A transfer writes exactly two entries, opposite signs, on two different accounts | `service/LedgerService.java:227-228` |
| `from_account` must differ from `to_account` | `service/LedgerService.java:208-210` |
| Funding is `EQUITY -> LIABILITY` and is how money enters the ledger | `service/LedgerService.java:298-300`, `api/FundingController.java:26` |
| A reversal is a new transaction with every posting's sign flipped; the original is never touched | `service/LedgerService.java:139-158` |
| A transaction may be reversed at most once | `V2__ledger_core.sql:33-34` |
| Entries are append-only, by trigger and by grant | `V4__immutability_triggers.sql`, `V12__least_privilege_app_role.sql:29-30` |
| `ledger_entries` has `id BIGSERIAL PRIMARY KEY` | `V2__ledger_core.sql:37` |
| `ledger_entries` has **no** unique constraint over `(transaction_id, account_id)` | `V2__ledger_core.sql:36-48` — the table's only constraints are the two amount CHECKs |
| The API exposes an entry id, as a pagination cursor | `domain/LedgerEntry.java:8-10`, `api/AccountController.java:52-58` |

## 5. The event contract

### 5.1 Topic and record

| Element | Value | Evidence |
|---|---|---|
| Topic | **`ledger.account-activity`** | `config/EventTopics.java:12` |
| Partitions / replicas, as created by the ledger | 3 / 1 | `config/EventTopics.java:25`, `:27`, `:29-32` |
| Record key | the account's `public_id`, as a UUID string | `outbox/AccountActivityPublisher.java:36-37`, `service/LedgerService.java:247-249` |
| Key and value serializer | `StringSerializer` for both | `application.yml:43-44` |
| Value | UTF-8 JSON object, one per ledger entry | `service/LedgerService.java:244-252`, `outbox/AccountActivityPublisher.java:36-37` |
| Producer `acks` | `all`, with producer idempotence on | `application.yml:42`, `:49` |
| Aggregate type / event type, stored but not on the wire | `ACCOUNT` / `account.entry_posted` | `domain/AccountActivityEvent.java:19-20`, `store/OutboxRepository.java:24-29` |

`aggregate_type` and `event_type` are columns of the outbox row. They are **not** put on the record:
the publisher writes only the topic, the key, the payload and one header
(`outbox/AccountActivityPublisher.java:36-38`). A consumer cannot filter on event type.

### 5.2 Headers

| Header | Requirement | Evidence |
|---|---|---|
| `event-id` | **Required.** The outbox row id, rendered as a decimal string and encoded UTF-8. Signed 64-bit, positive, ascending in publish order | `config/EventTopics.java:19`, `outbox/AccountActivityPublisher.java:38`, `V10__outbox.sql:11` |
| `traceparent` | Present in practice, not asserted anywhere. W3C trace context, injected by Spring's observation instrumentation on the producer side | `application.yml:37-40`, `:57-60`; `outbox/OutboxRelay.java:59`; `V11__outbox_trace_parent.sql:9` |

The ledger's own consumer treats a missing `event-id` as a programming error and throws
(`projection/AccountActivityProjection.java:63-68`); it deduplicates on that value alone (`:54-57`,
`store/ConsumedEventRepository.java:22-29`). This service must read the same header: the payload
carries nothing that identifies a delivery.

`traceparent` is only inferred to be on the wire. `TracePropagationTest` asserts that the consumer
*runs inside* the producing trace
(`src/test/java/com/baran/ledger/outbox/TracePropagationTest.java:61-69`), which is the observable
effect of the header, but no ledger test reads the header off a record. Treat it as best-effort
context, never as required.

### 5.3 Payload

The payload is the Jackson serialization of `AccountActivityEvent`
(`domain/AccountActivityEvent.java:12-17`; seven components @93eadc2 `:24-32`) under a snake-case naming strategy
(`application.yml:27-28`). The wire names are confirmed by SQL that reads the stored payload:
`payload->>'transaction_id'` and `payload->>'amount'`
(`src/test/java/com/baran/ledger/outbox/OutboxWriteTest.java:75`, `:78`).

| Field | Type on the wire | Meaning and constraints | Evidence |
|---|---|---|---|
| `transaction_id` | string, UUID | `ledger_transactions.public_id` of the transaction this entry belongs to | `domain/AccountActivityEvent.java:13`, `service/LedgerService.java:246` |
| `account_id` | string, UUID | `accounts.public_id` of the account posted to; equals the record key | `domain/AccountActivityEvent.java:14`, `service/LedgerService.java:246-249` |
| `amount` | integer | Signed minor units. Negative on the account debited, positive on the account credited. Never zero. Bounded to +/- 10 000 000 000 | `domain/AccountActivityEvent.java:10`, `:15`; `V2__ledger_core.sql:43-44`; `OutboxWriteTest.java:48-50` |
| `currency` | string, three letters | The currency of the account posted to; `TRY` in every ledger instance today | `domain/AccountActivityEvent.java:16`, `store/EntryRepository.java:32-42`, `domain/Money.java:13` |
| `tx_type` | string | `TRANSFER`, `FUNDING` or `REVERSAL` today — the enum's `name()`, which the naming strategy does not touch. The database admits more (§4), so the contract treats it as an open string (§5.5) | `domain/AccountActivityEvent.java:17`, `domain/TxType.java:7-11` |
| `entry_id` | integer, int64; **absent** on events written before `e3119e9` | `ledger_entries.id` of the entry this event describes, read back from the insert with `RETURNING id`. A reversal's events name the reversal's own entries, never the original's. Boxed `Long` on the producer, so "absent" never reads as entry zero | @93eadc2: `domain/AccountActivityEvent.java:18-19`, `:30`; `store/EntryRepository.java:38-46`; `service/LedgerService.java:153-154`, `:226-233`, `:245-249`; test `OutboxWriteTest.reversalEventsNameTheReversalsEntries` |
| `created_at` | string, `yyyy-MM-ddTHH:mm:ss.SSSSSSZ`; **absent** under the same condition | That entry's `created_at` column (`TIMESTAMPTZ DEFAULT now()`, so both entries of one transfer carry the same value), read back with `RETURNING created_at`. UTC, always six fractional digits, pinned by `@JsonFormat` because the default serializer drops trailing zeros | @93eadc2: `domain/AccountActivityEvent.java:20-22`, `:31-32`; `store/EntryRepository.java:42-45`; `V2__ledger_core.sql:42`; tests `OutboxWriteTest.eventNamesTheEntryItDescribes`, `createdAtAlwaysCarriesSixFractionalDigits` |

The ledger's README now carries an "Event contract" section describing the same seven fields, and
states that the contract only grows: fields are appended, never renamed, retyped or removed. The
ledger enforces that shape on the delivered bytes, read as plain JSON rather than through its own
record (@93eadc2 `OutboxRelayTest.deliveredPayloadKeepsTheContractShape`), and its own projection
still applies a five-field event (@93eadc2
`AccountActivityProjectionTest.eventWrittenBeforeTheEntryReferenceIsStillApplied`).

**The byte form is not stable.** The payload is stored in a `JSONB` column (`V10__outbox.sql:15`) and
read back with `payload::text` (`store/OutboxRepository.java:39`), so what reaches the broker is
PostgreSQL's normalization of the JSON, not Jackson's output: key order and insignificant whitespace
are PostgreSQL's. Never hash the payload bytes and never depend on field order; the `event-id` header
is the identity.

### 5.4 Delivery semantics

| Property | Behaviour | Evidence |
|---|---|---|
| Transactional outbox | The event row is written inside the transfer transaction | `service/LedgerService.java:236-253`, `store/OutboxRepository.java:21-30` |
| Delivery | At-least-once, deliberately. A crash between send and mark republishes | `outbox/OutboxRelay.java:16-23`, `:52-67` |
| Batch | 100 rows per relay tick, one at a time, each awaited before the next | `outbox/OutboxRelay.java:32`, `:55-67`, `outbox/AccountActivityPublisher.java:41` |
| Failure | Stops the batch rather than skipping it, to avoid reordering an account's events | `outbox/OutboxRelay.java:60-65` |
| Ordering | Per account (= per key = per partition) only, never across accounts: the relay selects `FOR UPDATE SKIP LOCKED` | `store/OutboxRepository.java:32-45`, `src/test/java/com/baran/ledger/projection/AccountActivityProjectionTest.java:53-82` |
| Deduplication | By `event-id`, in the same transaction as the effect | `projection/AccountActivityProjection.java:41-57`, `store/ConsumedEventRepository.java:15-29` |
| Offsets | `ack-mode: record`, auto-commit off, `auto-offset-reset: earliest` | `application.yml:55-63` |
| Retention | Published outbox rows are deleted by an archival job, so the topic is the only durable history a consumer has | `store/OutboxRepository.java:85-101`, `retention/OutboxArchivalJob.java` |

Both of a transfer's entries are announced (`service/LedgerService.java:231-232`), so one transfer
produces **two** records, on two different keys and possibly two different partitions.

### 5.5 `tx_type` values this service does not know

The producer's enum has three values (`domain/TxType.java:7-11`); the `valid_tx_type` CHECK admits
five (`V2__ledger_core.sql:25-26`). The ledger can therefore start emitting `FEE` or `ADJUSTMENT` in
a later phase without any change this service would see coming, and nothing prevents a sixth type.

The schema validates `tx_type` as a non-empty string and nothing more. What the reconciler does with
the value:

- **Known values** (`TRANSFER`, `FUNDING`, `REVERSAL`) are mapped to their meaning in this service.
- **Any other value is recognised but unmapped.** The event is schema-valid, so it is not
  dead-lettered. If its account is mapped to a source (FR-LED-2) the entry is projected like any
  other: the money moved on an account this service reconciles, and dropping the entry would turn a
  real movement into a phantom break on the PSP or bank side. `tx_type` is stored verbatim and no
  matching rule reads it, so an unknown type cannot change a match.
- The unmapped value is made visible rather than silent: logged at `WARN` the first time each
  distinct value is seen, and counted in a metric tagged with the value. That is the signal that
  this repository should learn the new type, without an outage in the meantime.

The previous behaviour — a closed enum, with an unknown type dead-lettered as contract drift — was
the recorded decision in ADR-0002 and is amended there.

## 6. What the event does not carry

As published since ledger commit `e3119e9`. Phase 0 found two more gaps, a timestamp and an entry
id; both are now on the wire (§5.3), and what this service does with them is under "Ledger change
landed" below.

- **No account type, no owner reference, no balance.**
- **No description.** `ledger_transactions.description` (`V2__ledger_core.sql:23`) is not announced.
- **No `aggregate_type` / `event_type` on the record**; they stay in the outbox row (§5.1).
- **No schema version field.** There is no `version`, `schema` or `type` discriminator in the
  payload. A seven-field and a five-field event are told apart only by whether `entry_id` and
  `created_at` are present.
- **No timestamp or entry id on events written before `e3119e9`.** Nothing was backfilled
  (ledger README, "Event contract", @93eadc2), so those events are still on the topic without them.

### Ledger change landed

The owner added `entry_id` and `created_at` to the account activity event in `..\ledger-payment-core`
(commit `e3119e9`, merge `93eadc2`). This repository did not touch the ledger. The contract in
`contracts/` now describes seven fields, with the two new ones optional (§5.3, `contracts/README.md`).

What the reconciler does with them:

- **`value_date` derives from `created_at` in `Europe/Istanbul`.** `created_at` is parsed as an
  `Instant`, converted with `atZone(Europe/Istanbul)`, and `toLocalDate()` is the value date. The
  zone is a zone id, not a fixed `+03:00` offset, and is configuration snapshotted onto each run
  (TDD §6). The Kafka record timestamp is never used: it is the relay's publish time (OQ-1, option A).
- **`entry_id` is the projection's entry identity.** One ledger entry is one projected row, enforced
  by a partial unique index on `ledger_entry_id` where it is not null (§7).
- **The `event-id` header stays the deduplication key** (FR-LED-3, INV-3). The insert is
  `ON CONFLICT (event_id) DO NOTHING`, so a redelivery is a no-op whatever its payload.
- **The same `entry_id` under a different `event-id` is a ledger fault, not a duplicate.** It means
  the ledger published one entry twice, which its outbox should make impossible. The `event-id`
  dedupe lets it through, so the unique index on `ledger_entry_id` rejects it. The consumer logs it
  at `ERROR` and dead-letters it (FR-LED-5). It is not swallowed: silently keeping the first row
  would hide a real fault in the ledger.
- **Five-field history is stored but never reconciled.** Such an entry has no `created_at`, so it
  has no `value_date`, `created_at` or `ledger_entry_id` in the projection. It is never back-dated
  from the Kafka record timestamp. An entry with no value date falls in no date window, so it is
  outside every run's scope and never produces a break: missing data is not a reconciliation
  discrepancy. The summary report shows how many such entries there are, separately from the run's
  matched, pending and broken counts.

## 7. Entry identity

One event is produced per ledger entry (`service/LedgerService.java:242-252`), and a transfer writes
two entries (`:227-228`), so two events describe one transfer.

**Since `e3119e9` the entry's identity is on the wire as `entry_id`**: `ledger_entries.id`, read back
from the insert (@93eadc2 `store/EntryRepository.java:38-46`) and put on the event built beside it
(@93eadc2 `service/LedgerService.java:245-249`). This is what the projection keys an entry on.

The two candidates Phase 0 had to choose between, before the field existed, are both still wrong
for this job, and the reasons are kept because they explain the rules in §6:

- The `event-id` header is unique per event (`V10__outbox.sql:11`,
  `outbox/AccountActivityPublisher.java:38`) and is what the ledger's own consumer deduplicates on
  (`projection/AccountActivityProjection.java:54-57`). It identifies a **delivery**, not an entry: it
  is an outbox sequence value, unrelated to ledger state, and a rebuilt or re-seeded ledger produces
  different ids for the same entries. That is why it stays the dedupe key, and also why a second
  `event-id` for an `entry_id` already seen is treated as a ledger fault rather than a new entry.
- `(transaction_id, account_id)` is unique per transaction *for the transaction types that exist
  today*, because a transfer's two accounts must differ (`service/LedgerService.java:208-210`) and a
  reversal flips the postings of such a transaction (`:140-158`). It is **not guaranteed by the
  schema**: `ledger_entries` carries no unique index over those columns (`V2__ledger_core.sql:36-48`),
  and the `valid_tx_type` CHECK already admits `FEE` and `ADJUSTMENT` (`V2__ledger_core.sql:25-26`),
  which a later ledger phase could write as several entries on one account within one transaction.

Events written before `e3119e9` carry no `entry_id`. For them the only identity is the `event-id`,
which is enough for deduplication. They take no part in reconciliation (§6), so no entry identity is
needed for them.

## 8. Local stack: images and ports

| Component | Image | Published port | Evidence |
|---|---|---|---|
| PostgreSQL | `postgres:16-alpine` | `127.0.0.1:5433` -> 5432 | `docker-compose.yml:3`, `:20` |
| Kafka (KRaft) | `apache/kafka:4.3.1` | `127.0.0.1:9092` (HOST listener); `kafka:29092` inside the network | `docker-compose.yml:34`, `:46-47`, `:56` |
| Tempo | `grafana/tempo:2.8.1` | `127.0.0.1:4318`, `127.0.0.1:3200` | `docker-compose.yml:69`, `:77-78` |
| Prometheus | `prom/prometheus:v3.4.1` | `127.0.0.1:9090` | `docker-compose.yml:122`, `:134` |
| Grafana | `grafana/grafana:12.0.2` | `127.0.0.1:3000` | `docker-compose.yml:143`, `:156` |
| Ledger app | built from `eclipse-temurin:21-jdk-alpine` to `21-jre-alpine` | `127.0.0.1:8080` (API); management 8081, unpublished under compose | `Dockerfile:3`, `:17`, `docker-compose.yml:94`, `application.yml:105` |
| Demo seeder | `curlimages/curl:8.14.1` | — | `docker-compose.yml:110` |

Everything is bound to `127.0.0.1` on purpose (`docker-compose.yml:10-18`). The tests use
`apache/kafka:4.3.1` through Testcontainers (`AbstractKafkaIntegrationTest.java:48`).

**Ports this service must avoid:** 3000, 3200, 4318, 5433, 8080, 8081, 9090 **and 9092**. TDD §5.1.1
lists the first set but omits 9092 and 4318; see [TDD corrections](#tdd-corrections).

## 9. Kafka: join the ledger's broker, or run our own

Determined from the compose file rather than chosen by preference:

- The ledger's broker advertises exactly two addresses: `HOST://127.0.0.1:9092` for processes on the
  host and `DOCKER://kafka:29092` for containers on its compose network (`docker-compose.yml:46-47`).
  A process on this machine can reach it at `127.0.0.1:9092` with no change to the ledger, which is
  read-only to us.
- A second broker of our own therefore cannot publish 9092 and must take another port.
- Consuming the ledger's topic needs only a distinct consumer group. The ledger's own group is
  `account-activity` (`projection/AccountActivityProjection.java:26`), and `auto-offset-reset:
  earliest` on a new group replays the topic from the beginning (`application.yml:53-55`), which is
  what NFR-REL-3 exercises anyway.

**Recommendation for Phase 1**, not implemented here: this service runs its own broker for local
development on a non-colliding published port, and gains a profile whose `bootstrap-servers` points
at `127.0.0.1:9092` for a live end-to-end demo against the ledger's stack. Tests use Testcontainers
either way (TDD §13). That keeps `docker compose up` in this repository self-contained (NFR-OPS-1)
while leaving the live demo a configuration change rather than a rewrite.

## 10. Producing ledger data for Phase 8

Not decided here; these are the facts that decide it.

- The ledger's HTTP API is `POST /v1/accounts` (`api/AccountController.java:23`), `POST /v1/funding`
  (`api/FundingController.java:26`), `POST /v1/transfers` and
  `POST /v1/transfers/{publicId}/reversals` (`api/TransferController.java:26`, `:61`). All mutating
  endpoints require `X-Client-Id` and `Idempotency-Key` (`api/TransferController.java:41-43`).
- Money can only enter the ledger through a funding transaction from an `EQUITY` account
  (`service/LedgerService.java:298-300`), so any generated dataset must create and fund accounts
  first.
- Driving the API produces real events but couples Phase 8 to a running ledger; publishing synthetic
  schema-valid events to the topic keeps this repository self-contained but proves nothing about the
  real producer. This is **OQ-4**.

---

## TDD corrections

Assumptions in `docs/settlement-reconciliation-tdd.md` (v1.1) that the ledger's source contradicts,
with the proposed replacement text.

### C-1 — Topic name (§15, line 635)

> topic `account.activity` keyed by account public id

The topic is **`ledger.account-activity`** (`config/EventTopics.java:12`). The ledger's own README
carried the stale name in its architecture diagram (`README.md:48` at `b32a5bf`), which is where the
TDD's value came from; the ledger has since corrected its README (commit `552547f`). Keying by
account public id is correct.

**Proposed text:** "topic `ledger.account-activity`, keyed by account public id".

### C-2 — `value_date` derivation (§6, lines 252-254)

> The ledger records `created_at` (UTC instant), not a value date. The projection derives
> `value_date = created_at` converted to `Europe/Istanbul`, then `toLocalDate()`. [...] Phase 0
> confirms the event carries `created_at`.

At Phase 0 it did not: the payload was five fields and none was a timestamp
(`domain/AccountActivityEvent.java:12-17`). Since ledger commit `e3119e9` the event carries
`created_at` (§5.3), but events written before it do not, and they are still on the topic.

**Proposed text:** keep the derivation, and replace the last sentence. The sentence "Phase 0
confirms the event carries `created_at`" was false when written and must not survive into v1.2. The
exact v1.2 wording is in the report on this contract update, which the owner applies to the TDD.

### C-3 — Entry id in the projection (§10, line 430)

> `ledger_entries (id UUID PK, event_id UNIQUE, ledger_entry_id, account_id, source_code, ...)`

`event_id` is a **signed 64-bit integer**, not a UUID (`V10__outbox.sql:11`), and it arrives as a
header rather than as a payload field. `ledger_entry_id` was not on the wire at Phase 0; it now is,
as `entry_id`, a JSON integer (§5.3), absent on older events.

**Proposed text:** `event_id BIGINT NOT NULL UNIQUE`, read from the `event-id` header;
`ledger_entry_id BIGINT NULL` from the `entry_id` field, with a partial unique index where it is not
null; `created_at` and `value_date` nullable for the same older events; and `tx_type TEXT NOT NULL`
as an explicit exception to "all type columns have CHECKs" (§5.5). The exact v1.2 wording is in the
same report.

### C-4 — Port collisions (§5.1.1)

> The ledger's local stack already uses 8080, 8081, 5433, 3000, 9090 and 3200.

It also publishes **9092** (Kafka, `docker-compose.yml:56`) and **4318** (Tempo OTLP,
`docker-compose.yml:77`).

**Proposed text:** "already uses 3000, 3200, 4318, 5433, 8080, 8081, 9090 and 9092".

### C-5 — Contract test source (§13, "Contract" row)

> Ledger event samples from the ledger repo validate against `contracts/`

The ledger repository contains no event sample files; its tests build events in code
(`src/test/java/com/baran/ledger/outbox/OutboxWriteTest.java:42-52`). There is nothing to copy.

**Proposed text:** "Synthetic samples in `contracts/samples/` validate against
`contracts/ledger-events.schema.json`; the schema is derived from the ledger's `AccountActivityEvent`
and re-checked against it whenever the ledger changes."

### C-6 — Spring Boot version (§5.1.1)

> Java 21, **Spring Boot 4** (same version as the ledger)

Not a contradiction but a pin: the ledger is on **4.1.1** (`pom.xml:10`) with Maven 3.9.9
(`.mvn/wrapper/maven-wrapper.properties:1`).

### Confirmed, no change needed

- One event per ledger entry, keyed by account public id (§15) — `service/LedgerService.java:242-252`.
- Consumer dedupe via a `consumed_events`-style table (§15) — `store/ConsumedEventRepository.java:22-29`.
- Signed entry amounts, positive increases the account (§6) — `domain/AccountActivityEvent.java:10`,
  `OutboxWriteTest.java:48-50`.
- The event carries the ledger transaction id, so a PSP can echo it (§6) —
  `domain/AccountActivityEvent.java:13`.
- `BIGINT` minor units, single currency TRY, `JdbcClient` without JPA, package `com.baran.ledger` (§15).

---

## Risks

- **R-1 — `tx_type` is wider in the database than in the producer.** The producer emits three values
  (`domain/TxType.java:7-11`); the `valid_tx_type` CHECK admits five (`V2__ledger_core.sql:25-26`).
  Mitigated: the schema accepts any string and an unknown value is recognised but unmapped (§5.5),
  so a new type is projected and reported rather than dead-lettered. What remains is that the
  service does not know what a new type *means* until this repository is updated; the `WARN` and the
  metric are how that gap is noticed.
- **R-2 — No schema version on the wire.** The payload has no discriminator (§6), so a breaking
  ledger change is only detectable by validation failure.
- **R-3 — Published outbox rows are deleted** (`store/OutboxRepository.java:85-101`). Kafka retention
  is then the only history; a long outage plus topic retention expiry means events this service never
  sees and cannot recover from the ledger without an API read it is forbidden to make (TDD §5.1).
- **R-4 — Ordering is per account only** (`store/OutboxRepository.java:32-45`). Nothing in matching
  may assume cross-account or cross-transaction order.

---

## Open questions

OQ-1 and OQ-2 are resolved by the ledger change, with the commit as evidence. The others are left
unresolved on purpose: none is guessed at, and nothing downstream should assume an answer.

### OQ-1 — What is the `value_date` of a ledger entry? — RESOLVED

**Resolved by ledger commit `e3119e9`** (merged as `93eadc2`): the event carries `created_at`, the
entry's own column, in UTC with six fractional digits (§5.3). This is option C below.

`value_date` is `created_at` converted to `Europe/Istanbul`, then `toLocalDate()`, with the zone as
configuration snapshotted onto each run (TDD §6). Events written before `e3119e9` have no
`created_at`; they get no value date and are never back-dated from the record timestamp (§6,
"Ledger change landed").

The options that were weighed are kept, because the reasoning explains why C was worth a ledger
change:

| Option | What it gives | What it costs |
|---|---|---|
| **A. Kafka record timestamp** (`ConsumerRecord.timestamp()`, `CreateTime`) | Needs no change anywhere; it is fixed on the record, so re-reading the same record yields the same date | It is the time the **relay published**, not the time the ledger committed. The relay ticks every 200 ms (`application.yml:69`) but a failing batch stops and retries on a later tick (`outbox/OutboxRelay.java:60-65`), and a republish after a crash produces a *new* record with a new timestamp for the same event. Around midnight in `Europe/Istanbul` an entry can land on the wrong value date, and in the failure case the error is unbounded |
| **B. Consumer receipt time** (`received_at` in our projection) | Trivial and always available | Wrong by however long this service was down. Replaying the topic into a fresh database assigns today's date to all history, which breaks re-derivation of any past run and silently changes past reports |
| **C. Ask for `created_at` in the payload** (a ledger change) | Correct by construction; one field, already on the row the outbox is written beside | Requires a change in `..\ledger-payment-core`, which is read-only in this project and outside its scope. Needs the owner's decision and a ledger phase |
| **D. Read it from the ledger's HTTP API** (`GET /v1/transfers/{publicId}`) | Correct value, no ledger change | Forbidden by TDD §5.1 ("never calls the ledger's API") and by INV-9; reintroduces the coupling the architecture exists to avoid, and puts a synchronous dependency in the consumer path |
| **E. Carry it as a Kafka header** instead of a payload field | Same as C | Same as C, with no advantage over it, and a header is easier to drop than a field |

A was the only option available without changing the ledger or breaking a stated rule, and it is
wrong at exactly the boundary that matters for a date-windowed reconciliation — which is what made
the ledger change worth making instead.

### OQ-2 — What identifies a single ledger entry? — RESOLVED

**Resolved by ledger commit `e3119e9`** (merged as `93eadc2`): the event carries `entry_id`, the
entry's `ledger_entries.id`, as a JSON integer (§5.3).

`entry_id` is the projection's entry identity, carried by a partial unique index on
`ledger_entry_id`. The `event-id` header stays the deduplication key. The two alternatives Phase 0
weighed — the `event-id` header as the natural key, or the pair `(transaction_id, account_id)` —
stay rejected for the reasons in §7: the first identifies a delivery rather than an entry, and the
second is unique today only by accident of which transaction types exist.

### OQ-3 — How is a PSP clearing account represented?

The ledger has five account types (`domain/AccountType.java:4-10`), no notion of a clearing or
settlement account, and no attribute that would mark one (§3). Whether such an account is `ASSET` or
`LIABILITY` is an accounting decision the ledger does not make for us. Note that only `EQUITY` may go
negative (`V6__derived_allow_negative.sql:12-13`) and that funding is fixed as `EQUITY -> LIABILITY`
(`service/LedgerService.java:298-300`), which constrains how a generated dataset can move money into
such an account. **Not resolved here.**

### OQ-4 — Does Phase 8 drive the ledger's API or publish synthetic events?

The facts are in §10; the decision is the owner's.

### OQ-5 — License

The ledger has no `LICENSE` file, so "match the ledger's" (TDD §15) has nothing to match.
**Not resolved here.**
