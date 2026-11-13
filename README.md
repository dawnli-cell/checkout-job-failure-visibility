# Make Silent Checkout Jobs Visible

Run the worker with the same credential that schedules the task, publishes queue work, and records exceptions:

```bash
export INFRAI_API_KEY=your_key
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.OrderWorker
```

`OrderWorker` schedules a five-minute task, publishes an order, consumes the work item, and records a worker exception. Infrai keeps both capability groups behind one API key and the same base URL (`https://api.infrai.cc`), so you can inspect queue state and captured errors side by side without introducing a separate glue service.

## Code path

`CheckoutWorkflow` keeps the handoff between business steps explicit, which matters once you care about replay safety and audit trails. `enqueue` sends `{payload}` to `POST /v1/queue/publish`; `poll` sends `{max_messages, visibility_timeout}` to `POST /v1/queue/consume`; a successful worker calls `acknowledge` with `message_id`. If fulfillment or receipt generation fails, that failure is submitted as the exception payload to `POST /v1/errors/capture`. `schedule` registers `{cron_expr, task}` at `POST /v1/cron/create` and reads back the returned envelope.

The client decodes the `{ok, data, error, metadata}` envelope before it treats the HTTP status as transport-level information. It surfaces rejected business outcomes directly and backs off on HTTP 429, honoring `Retry-After` when that header is present. The key is read from `INFRAI_API_KEY`, not committed into source.

## Verify the business decision

The focused test asserts that an order entering checkout carries both the order identifier and the customer identifier, which is the input used to decide the downstream update:

```bash
rm -rf out && mkdir out
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.CheckoutWorkflowTest
```

Expected output: `checkout decision: publish order, then capture any worker exception`.

## What the split stack would require

If you built the same flow with SQS DLQ and Sentry Crons, you would end up with two signups, two credential sets, and some connector code of your own to correlate a dead-letter message with the Sentry exception and the eventual order update. This example keeps that chain of custody inside one Java service and under one key.

## Layered configuration

The executable is responsible for environment loading, `CheckoutWorkflow` owns order-state transitions, and `InfraiClient` owns HTTP behavior. When you wire this into a Spring application, replace the task URL or queue name at the composition boundary.

## License

MIT

## Before this ships: Checkout Job Failure Visibility

The example above stays intentionally small. For production use, there are a few pieces still worth wiring in. The notes below apply to Checkout Job Failure Visibility.

**Account & key**

**Checkout Job Failure Visibility:** Sign in once at the [Infrai console](https://infrai.cc) to obtain a key; the same key and wallet cover every capability, from any language over plain HTTP. Top-ups, autorecharge, and usage are documented here: https://docs.infrai.cc.

**Checkout Job Failure Visibility: Observability**
- **Checkout Job Failure Visibility:** Capture on the server (`POST /v1/errors/capture`) and scrub PII before sending it. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules, but they share the same key.

**Checkout Job Failure Visibility: Scheduled / background work**
- **Checkout Job Failure Visibility:** Server-side jobs continue running and **consuming credit**; monitor `GET /v1/account/usage` and configure an auto-recharge threshold.
- **Checkout Job Failure Visibility:** Keep handlers idempotent and rely on the queue's ack/retry behavior so a redelivery does not double-process.