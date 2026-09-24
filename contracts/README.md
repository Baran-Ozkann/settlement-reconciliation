# Contracts

The wire contract between `ledger-payment-core` and this service. It is the **only** coupling between
the two: this service never reads the ledger's database and never calls its API (TDD §5.1).

Everything here was derived from the ledger's source. The evidence, with file and line numbers, is in
[`../docs/ledger-integration-notes.md`](../docs/ledger-integration-notes.md) §5. That document is the
reason this one is believable; this one is what code validates against.

## What a record looks like

| Element | Value |
|---|---|
| Topic | `ledger.account-activity` |
| Partitions (as the producer creates the topic) | 3, replication factor 1 |
| Key | the account's public id, a UUID string, UTF-8 |
| Value | a JSON object, UTF-8, matching `ledger-events.schema.json` |
| Required header | `event-id` |
| Optional header | `traceparent` |

One record is one **ledger entry**, not one transaction: a transfer produces two records, on two
different keys, and they may land on different partitions.

## The `event-id` header

`event-id` is part of the contract, and a record without it cannot be processed.

- **Required.** The ledger's own consumer throws when it is missing, and so must this one. There is
  nothing in the payload that identifies a delivery.
- **Value**: the producer's outbox row id — a signed 64-bit integer, rendered as a decimal string and
  encoded UTF-8 (for example the five bytes `12345`). Not a UUID, not binary.
- **Unique per event**, and ascending in publish order per producer.
- **It is the deduplication key.** Delivery is at-least-once by design; the same `event-id` may
  arrive any number of times and must produce exactly one row (FR-LED-3, INV-3).
- It identifies a *delivery*, not a ledger entry. Entry identity is settled but not yet shipped: the
  ledger is being changed to publish `entry_id` (OQ-2 in the integration notes). This document
  describes what the ledger publishes today, so that field is not in the schema yet.

`traceparent` (W3C trace context) is normally present, injected by the producer's observability
instrumentation. Use it for correlation if it is there; never require it, and never fail a record for
the lack of it.

## The payload

`ledger-events.schema.json`, JSON Schema draft 2020-12. Five required fields: `transaction_id`,
`account_id`, `amount`, `currency`, `tx_type`. The schema carries the reasoning for each constraint.

Two deliberate choices:

- **Unknown properties are allowed.** The payload has no schema version field, so a purely additive
  change in the ledger would otherwise fail every record at once and stop the projection. A missing
  or malformed known field still fails.
- **UUIDs are checked with `pattern`, not `format`.** In draft 2020-12 `format` is an annotation
  unless a validator is configured to assert it, so a `format`-only schema silently accepts
  `account-42` in some validators. The pattern fails it everywhere.

Do not depend on the byte form: the ledger stores the payload as `JSONB` and hands the broker
PostgreSQL's normalization of it, so key order and whitespace are not the producer's and not stable.

## Samples

`samples/valid-*.json` must validate; `samples/invalid-*.json` must each fail, for the reason in
their name. All values are synthetic — these ids belong to no ledger instance.

| Sample | Why it is there |
|---|---|
| `valid-transfer-debit.json` | the debit half of a transfer: negative amount |
| `valid-transfer-credit.json` | the credit half of the same transaction id: positive amount |
| `valid-funding-credit.json` | `FUNDING`, at the maximum permitted amount |
| `valid-reversal-debit.json` | `REVERSAL`, the type a correction arrives as |
| `invalid-amount-zero.json` | zero is not a movement; the ledger forbids it at the table |
| `invalid-amount-fractional.json` | minor units are integers; `1250.5` is not an amount |
| `invalid-amount-above-maximum.json` | one over the ledger's per-entry bound |
| `invalid-missing-transaction-id.json` | the field a PSP line is matched on, absent |
| `invalid-account-id-not-a-uuid.json` | source mapping is by account id; a non-UUID cannot map |
| `invalid-currency-lowercase.json` | `try` is not an ISO 4217 code |
| `invalid-tx-type-unknown.json` | `FEE` is contract drift: the ledger's schema admits it, its producer cannot emit it |

## Verifying

The samples are verified by a Maven test added in Phase 1: it loads
`ledger-events.schema.json` with the `networknt` JSON Schema validator (a Java library, pinned to a
version that implements draft 2020-12), asserts that every `samples/valid-*.json` validates and that
every `samples/invalid-*.json` fails, and runs as part of `mvnw.cmd verify`. Adding a sample or
changing a constraint is therefore checked by the build, with no separate tool and no network beyond
Maven's own dependency resolution.

From Phase 3 the same schema is what the consumer validates each record against at runtime
(FR-LED-6), so "the schema is right" and "the consumer enforces the schema" stay one fact rather than
two.

Until that test exists there is no command here to run. Phase 0's verification was done with
`ajv-cli`, before the rule settling which tools may reach the network: 4 valid samples passed and all
7 invalid samples failed, each on the keyword it was written to exercise. That result stands as a
record of what was checked in Phase 0; it is not a procedure to repeat.
