# Optional RabbitMQ + transactional outbox

Milestone 13 is local development and CI functionality, **not deployed to Render
Free**. The live demo remains synchronous and does not require a broker. No cloud
resources, deployment hooks, credentials or environment settings are provisioned.

## Boundaries and event flow

1. `OrderService.create` reserves stock, creates the order/fulfillment plan and
   audit entry, then records `ORDER_RESERVED`, all within the existing transaction.
   The existing idempotency response is saved in that transaction too.
2. A successful cancellation records `ORDER_CANCELLED` alongside order state,
   stock release, fulfillment cancellation and audit. A terminal retry returns
   without another event.
3. Whole-order dispatch records `ORDER_SHIPPED` alongside all shipment/task state,
   stock deduction, order transition and audit. It is not a per-package event.
4. `OrderEvents.record` requires an existing transaction (`MANDATORY`); JDBC uses
   the same datasource/transaction manager as the business services. An exception
   anywhere rolls back both the business change and its event. Event insertion
   failures therefore fail the business operation; broker availability never does.
5. A separate publisher commits a short claim transaction, sends outside database
   locks, and records the confirmed outcome. The consumer commits its inbox marker
   and operational ledger row before the listener acknowledges the message.

No external notifications are sent. Consumer processing cannot reserve, release,
ship or otherwise alter order/inventory truth. The append-only-in-use derived
ledger is useful for integration diagnostics, not a replacement for business audit
or the existing analytics source. No new frontend screen is added.

## Contract and migration V9

Example (synthetic identifiers):

```json
{
  "eventId": "00000000-0000-4000-8000-000000000001",
  "eventType": "ORDER_RESERVED",
  "schemaVersion": 1,
  "orderId": "00000000-0000-4000-8000-000000000002",
  "occurredAt": "2026-10-19T12:00:00Z",
  "correlationId": "00000000-0000-4000-8000-000000000002",
  "payload": { "totalUnits": 3 }
}
```

`ORDER_CANCELLED` and `ORDER_SHIPPED` use the same fields and total ordered units.
The application generates the event UUID. Correlation is the order UUID, never an
untrusted request header. UTC occurrence time is recorded during the transaction,
not the exact commit timestamp. There are no customer names/addresses, usernames,
reasons, tracking numbers, prices, credentials or tokens. The receiver reads a
fixed Java record, ignores AMQP type headers and rejects unsupported versions,
invalid values and bodies over 16 KiB. A future incompatible schema needs explicit
consumer compatibility and rollout planning; version 1 is the only supported one.

V9 adds three tables, without changing existing rows or creating accounts:

- `event_outbox`: unique event ID and `(order_id,event_type)`, fixed schema/type,
  strictly field-limited JSON payload, pending/in-flight/published/failed state,
  attempt count, next attempt, lease token/expiry, publish time and fixed error code.
  Partial indexes support due pending work and expired leases.
- `event_inbox`: event ID primary key, canonical decoded-content SHA-256 and
  processed timestamp. A conflicting same-ID payload is rejected.
- `order_event_ledger`: one event ID referencing the inbox, order ID, type,
  occurrence/processing times and total units. It deliberately has no order FK:
  consumption is independent of order reads and event arrival order.

No historic orders are backfilled. The uniqueness constraint assumes each of these
transitions occurs once per order under the existing lifecycle. Existing users,
roles, constraints and migrations V1–V8 remain unchanged. V9 runs without RabbitMQ.
Never edit a migration after deployment. An older application may ignore these
additive tables, but cannot emit missing events for operations performed during a
rollback. A rollback is not an event-history reconstruction; review Flyway version
validation and application compatibility before any separately approved release.
Do not drop tables/reset Neon to roll back this feature.

## Delivery, claims and recovery

`PENDING -> IN_FLIGHT -> PUBLISHED`, or `IN_FLIGHT -> PENDING/FAILED`.
Each worker atomically claims at most one row using `FOR UPDATE SKIP LOCKED`, a
random lease token and a 30-second expiry. Attempts increment when claimed. Network
I/O happens after commit, not while holding row locks. Updates are fenced by the
lease token, so a stale worker cannot mark another worker's attempt successful.
An expired lease can be reclaimed; an expired eighth attempt becomes `FAILED`.

A mandatory persistent message must receive a positive correlated publisher
confirmation **and no return** before it becomes `PUBLISHED`. Nacks, returns,
connection errors and confirmation timeouts remain retryable. Default confirm
wait is 5 seconds (validated maximum 10); connection/handshake timeouts are 3
seconds and channel RPC timeout 5 seconds. Backoff is 2^attempt seconds, up to
256 seconds, with eight total attempts. Polling defaults to one event/second per
instance; this is intentionally modest throughput, not a bulk event platform.

A crash after broker acceptance but before the database update, or a lost ACK,
can deliver the same event twice. Expiry is not proof that the original worker
has stopped. This is **at-least-once delivery**, not exactly-once messaging.
Retries are bounded: FAILED records require operator review and do not silently
vanish. Published means accepted/routed by RabbitMQ, not processed by the consumer.

Consumer inbox insertion (`ON CONFLICT DO NOTHING`) and ledger insertion share a
transaction. Concurrent duplicate IDs wait on the unique constraint. Identical
content becomes a no-op; different content under the same ID is rejected.
A rollback leaves neither row, so a later retry is safe. The ledger does not assume
cross-event ordering: multiple workers and retries can deliver events out of order.
The observed database effect is deduplicated while the inbox is retained.

## Broker topology and poison messages

- Durable direct exchange: `operationsflow.orders.v1`.
- Durable quorum queue: `operationsflow.order-ledger.v1`, bindings for the three
  event type names; persistent messages and AUTO listener acknowledgment after
  successful processing.
- Durable dead-letter exchange/queue: `operationsflow.orders.dead.v1` /
  `operationsflow.order-ledger.dead.v1`, routing key `rejected`.
- Three listener attempts with 100/200 ms delay. Exhausted/invalid messages are
  rejected without requeue. The quorum queue uses at-least-once dead lettering,
  `reject-publish` overflow and a delivery limit of 5 for repeated broker delivery
  after crashes. DLQ messages are not automatically recycled.

The pinned local image is `rabbitmq:4.3.6-management-alpine`. A single local quorum
node offers persistence across restart with its volume, **not high availability**.
Volume loss, disk exhaustion, malicious privileged DB/broker writes and total
broker loss are outside the guarantee. There is no cross-system atomic commit.

## Local startup (optional)

Use only synthetic local data and a developer-owned ignored `.env`; replace the
RabbitMQ placeholder values in `.env.example`. These are not production defaults.
Follow the README for local PostgreSQL and existing backend/database/bootstrap
settings. From the repository root:

```sh
docker compose --env-file .env -f compose.messaging.yml up -d --wait
```

The AMQP port is bound to **127.0.0.1:5672** only. Management port 15672 is not
published. The broker has a separate named volume and a 768 MiB / 1 CPU limit; it
is not included in `compose.yml`, `compose.full.yml` or `Dockerfile.demo`.

Provide `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` through your shell/IDE's local
environment (not source control, command-line literals or screenshots). Compose's
`.env` is not automatically the Java process environment. Run the backend with:

```sh
cd backend
SPRING_PROFILES_ACTIVE=messaging ./mvnw spring-boot:run
```

Optional environment overrides: `RABBITMQ_HOST` (localhost) and `RABBITMQ_PORT`
(5672). Do not expose this plain-AMQP local configuration to a public network.
Default/demo exclude Rabbit auto-configuration; explicit messaging beans require
`messaging & !demo`. Even both profiles together do not start a broker connection,
listener or publisher. No Rabbit health indicator can break default/demo readiness.
The AMQP jars increase the packaged image size; constrained image CI still runs.

Stop without deleting data:

```sh
docker compose --env-file .env -f compose.messaging.yml down
```

Adding `--volumes` deliberately destroys local broker data, including queued/DLQ
messages. It never changes the GitHub repository or any hosted service.

## Safe diagnostics and operator recovery

When messaging is enabled, authenticated ADMIN may GET `/api/v1/messaging/status`
for aggregate outbox state counts and processed ledger count. Every other role
and anonymous access are denied. No payload list, replay/mutation API or public
Actuator exposure is introduced. Publisher logs contain event ID, fixed result
code, attempt and lease ownership; consumer failure logs omit bodies/causes.
Transport framework logs may identify the local broker endpoint; do not enable
wire-level debug logging or use real customer data.

For local failed records: inspect counts, fixed error codes and broker queue/DLQ
counts using local administrative tooling. Repair connectivity/routing or consumer
schema handling first. A deliberately reviewed DB maintenance transaction may
reset a selected FAILED event to PENDING with attempts=0 and next_attempt_at=now(),
preserving its ID, type and content. Do not mass-reset rows or change IDs to bypass
deduplication. No automatic replay tool or production runbook execution is provided.
For DLQ recovery, validate the cause and republish the original envelope/ID after
repair; remove the dead letter only after confirmed republishing. Replaying an
already processed event is a no-op. An already PUBLISHED event can still be in DLQ.
Tests exercise controlled repair/replay against disposable containers only.

Default/demo still write outbox rows but never drain them. No purge/retention job is
implemented, so storage grows by one row per transition. Before any future live
rollout, decide retention/capacity and whether unpublished history should be sent;
do not indiscriminately enable messaging against a demo database. Inbox deletion
would remove duplicate protection. Authentication, authorization, CSRF, audit,
stock transactions and live environment settings are not weakened or changed.

## Verification and deferred work

```sh
cd backend
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress -Pmessaging-tests verify
```

Normal verification includes all existing tests, PostgreSQL outbox/consumer tests,
V8→V9 migration tests, contract/confirm tests and profile isolation tests. The
`messaging-tests` profile selects `*BrokerIT` for Failsafe, while unit tests still
run. The separate GitHub Actions **Messaging integration** job runs PostgreSQL and
RabbitMQ Testcontainers. It must not silently skip when Docker is unavailable.
The original backend/frontend/audit/Compose/container/constrained-demo CI stays
intact. No tests access Render or Neon.

The existing release-readiness verifier checks the original five CI jobs only;
the owner must additionally confirm **Messaging integration** passes for the same
commit. This milestone does not change release/deployment automation.

Implemented tests cover transition retries/rollback, concurrent claims, lease
recovery and fencing, bounded failures, confirmations/returns/timeouts, broker
outage/recovery, duplicate consumption, transactional consumer failure and poison
DLQ/replay. CI links and actual counts belong in the PR, not speculative claims.
Deferred: hosted broker, clustered HA, TLS/rotated broker service identities,
alerting, retention/archival, operator replay UI, broad load testing and additional
consumers. Do not provision RabbitMQ on the constrained Render Free demo.
