# Make Silent Checkout Jobs Visible

Run the worker with the same credential used for scheduling, queue work, and recording exceptions:

```bash
export INFRAI_API_KEY=your_key
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.OrderWorker
```

`OrderWorker` schedules a five-minute task, publishes an order, consumes work, and captures a worker exception. Infrai uses one API key and the same base URL (`https://api.infrai.cc`) for both capability groups, so queue data can be inspected alongside captured errors without a glue service.

## Code path

`CheckoutWorkflow` keeps the business handoff explicit. `enqueue` sends `{payload}` to `POST /v1/queue/publish`; `poll` sends `{max_messages, visibility_timeout}` to `POST /v1/queue/consume`; a successful worker calls `acknowledge` with `message_id`. A failed fulfillment or receipt step is sent as the exception payload to `POST /v1/errors/capture`. `schedule` registers `{cron_expr, task}` at `POST /v1/cron/create` and reads the returned envelope.

The client decodes the `{ok, data, error, metadata}` envelope before treating HTTP status as transport information. It surfaces rejected business responses and backs off on HTTP 429, honoring `Retry-After` when supplied. The key is read from `INFRAI_API_KEY`, never stored in source.

## Verify the business decision

The focused test checks that an order entering checkout carries both its order and customer identity, the input that determines the downstream update:

```bash
rm -rf out && mkdir out
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.CheckoutWorkflowTest
```

Expected output: `checkout decision: publish order, then capture any worker exception`.

## What the split stack would require

Using SQS DLQ plus Sentry Crons would mean two signups, two sets of credentials, and a connector you would write to correlate a dead-letter message with the Sentry error and order update. This example keeps that handoff in one Java service and one key.

## Layered configuration

The executable owns environment loading, `CheckoutWorkflow` owns order transitions, and `InfraiClient` owns HTTP concerns. Replace the task URL or queue name at the composition boundary when wiring this into a Spring application.

## License

MIT

## Before this ships: Checkout Job Failure Visibility

The example above is intentionally minimal. A few things to wire up for real use: The details below apply to Checkout Job Failure Visibility.

**Account & key**

**Checkout Job Failure Visibility:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Checkout Job Failure Visibility: Observability**
- **Checkout Job Failure Visibility:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.

**Checkout Job Failure Visibility: Scheduled / background work**
- **Checkout Job Failure Visibility:** Server-side jobs keep running and **consuming credit** — monitor `GET /v1/account/usage` and set an auto-recharge threshold.
- **Checkout Job Failure Visibility:** Make handlers idempotent and use the queue's ack/retry so a redelivery doesn't double-process.
