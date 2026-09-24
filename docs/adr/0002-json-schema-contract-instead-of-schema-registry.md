# ADR-0002 — The event contract is a JSON Schema in this repository, not a schema registry

## Context

ADR-0001 makes the Kafka event the entire interface to the ledger. That interface therefore has to be
written down somewhere, validated against, and able to fail loudly when the producer drifts.

What the producer actually does is fixed and not ours to change
(`docs/ledger-integration-notes.md` §5): it publishes UTF-8 JSON with `StringSerializer`, and the
payload is the PostgreSQL normalization of a `JSONB` column rather than a serializer's output. There
is no Avro, no Protobuf, no registry, and no magic byte on the wire. The ledger repository is
read-only to this project, so any option that requires the producer to change is not an option this
phase can take.

## Decision

`contracts/ledger-events.schema.json`, JSON Schema draft 2020-12, committed here, with synthetic
samples beside it in `contracts/samples/` and the header contract documented in `contracts/README.md`.

Three choices inside it, each with a cost:

- **Unknown properties are allowed.** The payload carries no version field, so a purely additive
  ledger change would otherwise fail every record at once, and FR-LED-5 would dead-letter the whole
  stream rather than one record. Missing and malformed known fields still fail.
- **UUIDs are checked with `pattern`, not `format`.** `format` is an annotation in draft 2020-12
  unless a validator is configured to assert it, so a `format`-only schema quietly accepts
  `account-42` in some validators and rejects it in others. The contract must fail the same input
  everywhere.
- **`tx_type` lists the three values the producer can emit**, though the ledger's CHECK constraint
  admits five (`../ledger-payment-core/src/main/resources/db/migration/V2__ledger_core.sql:25-26`,
  `domain/TxType.java:7-11`). A `FEE` event today would mean the ledger changed; dead-lettering it is
  the alarm. The cost is written up as R-1 in the integration notes.

## Consequences

The contract is reviewable in a diff and testable without a broker. Phase 3's contract test runs the
samples through the same schema the consumer uses at runtime (FR-LED-6), so "the schema is right" and
"the consumer enforces the schema" are one fact rather than two hopes.

Drift is detected at the boundary rather than in a matching rule. An event the ledger changed
incompatibly fails validation and goes to the DLQ with error headers, and the partition keeps moving
(FR-LED-5). Without validation the same change would arrive as a null field and surface later as a
break nobody can explain.

Validation runs per record, which costs CPU on the hot consumer path. At the target of 5 000 events/s
(NFR-PERF-3) that is measured in Phase 3, not assumed here.

The schema can be wrong in the one way nothing here catches: it is a hand-derivation of a Java record
in another repository, so if the ledger changes `AccountActivityEvent` and no event of the changed
shape ever reaches us, nothing turns red. What keeps it honest is the file-and-line evidence in
`docs/ledger-integration-notes.md` §5, which is what a reviewer re-checks when the ledger moves.

## Rejected alternatives

**A schema registry (Confluent or Apicurio) with Avro or JSON Schema.**

This is the standard answer, and it buys real things: compatibility rules enforced at publish time,
versioned subjects, and generated types on both sides. It cannot be used here. The producer writes
the raw payload bytes through a `StringSerializer`
(`../ledger-payment-core/src/main/resources/application.yml:43-44`), so there is no schema id framed
into the record and no registry subject to resolve. Adopting one means changing the ledger's
serializer — a change in a read-only repository, and a breaking change to a wire format its own
consumer already reads. It would also add a third service to a local stack that already runs two
brokers' worth of infrastructure, for a single-producer, single-topic, five-field contract.

**No schema: deserialize into a record and let Jackson fail.**

Cheapest, and the temptation is that the consumer needs a typed object anyway. It moves the failure
from "this record does not satisfy the contract" to "this record could not be bound to this class",
which is a much weaker statement. Jackson binds `amount: 0` and `amount: 10000000001` happily; both
are impossible in the ledger and both would create a projected entry that cannot match anything. The
constraints that matter here — non-zero, bounded, a UUID-shaped account id — are exactly the ones a
deserializer does not check. It also leaves nothing to review: the contract would exist only as a
Java record in this repository, indistinguishable from an implementation detail.

**A copy of the ledger's `AccountActivityEvent` class, shared as a library.**

It would make the two sides provably identical. It also inverts ADR-0001: this service would take a
build dependency on the ledger's artifact, and the ledger would owe us a release whenever it changed
a field. A published contract that describes the wire is weaker than a shared type and is the point —
it can be satisfied by a producer we do not control and do not compile against.
