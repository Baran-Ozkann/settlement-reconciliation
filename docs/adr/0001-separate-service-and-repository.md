# ADR-0001 — Reconciliation is a separate service in a separate repository

## Context

Reconciliation compares what the ledger recorded against what a PSP reports and what a bank paid out.
It needs the ledger's postings. The obvious cheaper shapes are to add it to `ledger-payment-core` as
a package, or to keep it a separate deployable that reads the ledger's database.

The two systems answer different questions. The ledger proves **internal** consistency: entries sum
to zero, a balance equals the sum of its entries, an idempotency key maps to one transaction. Those
are statements about rows the ledger itself wrote, and every one of them is enforced in its database
(`../ledger-payment-core/src/main/resources/db/migration/V3__balanced_transaction_trigger.sql` and
its neighbours). Reconciliation proves **external** consistency, and every input it needs — a
settlement report, a bank statement — is a file that arrives late, out of order, and sometimes wrong.

Their failure modes differ accordingly. A reconciliation bug must never be able to stop a transfer.

## Decision

A separate service in a separate repository, with its own PostgreSQL database and its own
deployment. It consumes the ledger's `ledger.account-activity` topic and nothing else. It never
writes to the ledger, never reads the ledger's database, and never calls the ledger's HTTP API. The
only coupling is `contracts/`.

The ledger repository is read-only to this project. This one takes the ledger's conventions
deliberately — Java 21, Spring Boot 4.1.1, Maven wrapper, `JdbcClient` with explicit SQL, no ORM,
`BIGINT` minor units — so a reviewer reading both reads one style; but it copies no code.

## Consequences

The ledger stays untouched. Nothing in this project can make a transfer slower, or fail, or wrong —
the strongest property available, and it follows from the deployment boundary rather than from
discipline.

The failure domain is contained the other way too. A 200 MB statement upload, a million-line parse
under a memory cap, an operator running an expensive summary query: none of it competes with the
transfer path for connections, heap or locks.

The two can be read as one story. The ledger's README explains internal consistency; this repository
explains external consistency and links to it. Keeping the vocabulary identical is what makes that
work — "entry", "minor units", "break proof" mean the same thing in both.

What it costs, stated plainly:

- **Duplication.** `Money`, the test base classes, the CI shape and the rule guard exist twice. That
  is accepted: a shared library between two repositories with one owner would buy less than the
  coupling it creates.
- **No joins.** A break cannot be investigated with a query spanning both databases; it is answered
  by what the projection stored, so the projection has to store enough. That constraint is visible in
  TDD §10 and is why the projection keeps amount, currency and identifiers rather than a reference.
- **The event is the whole interface.** Anything the ledger does not publish is unavailable, and
  asking for more is a change to another repository on someone else's schedule. Phase 0 already found
  two such gaps: no timestamp and no entry id
  (`docs/ledger-integration-notes.md` §6, §7). Reading the ledger's API instead would dissolve the
  boundary this record exists to draw, so those stay open questions rather than quiet workarounds.
- **Two stacks to run locally.** Ports had to be chosen around the ledger's
  (`docs/ledger-integration-notes.md` §8).

## Rejected alternatives

**A package inside `ledger-payment-core`.**

It removes the duplication and the port juggling, and it makes reconciliation a direct SQL join
against `ledger_entries` — genuinely simpler for Stage A. The failure is what it does to the ledger's
guarantees. Reconciliation ingests untrusted files: a 200 MB upload, a parser bug, an operator export
over a million breaks. In one process that is the transfer path's heap, connection pool and CPU. The
ledger's whole argument is that a transfer is correct and cannot be talked out of it; sharing a JVM
with a batch file processor weakens that argument for a convenience.

There is a schema failure too. Reconciliation needs to write — matches, breaks, break events — and
the ledger's application role is deliberately granted almost nothing
(`../ledger-payment-core/src/main/resources/db/migration/V12__least_privilege_app_role.sql:29-39`).
Either reconciliation gets a wider role in the ledger's database, which is exactly the privilege that
migration removed, or it gets a second schema and the boundary is back, drawn in a worse place.

**A separate service reading the ledger's database directly.**

Tempting because it solves both Phase 0 gaps at once: `ledger_entries.created_at` and
`ledger_entries.id` are right there in the table. It makes this service's correctness depend on the
ledger's physical schema, which the ledger changes by migration whenever it likes and has already
changed twelve times. A column rename in another repository would break reconciliation in production
with no test in either repository noticing. Worse, a reader holding a transaction against the
ledger's tables interferes with the transfer path it is supposed to be independent of. The event
contract is versionable, testable against samples, and cannot take a lock.
