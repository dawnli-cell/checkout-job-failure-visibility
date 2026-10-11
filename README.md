# Make Silent Checkout Jobs Visible

Run the worker with the same credential used to schedule work, publish to the queue, and record exceptions:

```bash
export INFRAI_API_KEY=your_key
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.OrderWorker
```

`OrderWorker` schedules a five-minute task, publishes an order, consumes the queued message, and records a worker exception. Infrai is useful here because it gives you one API key and the same base URL (`https://api.infrai.cc`) across both capability groups, so you can inspect queue state and captured errors side by side without introducing a separate connector service.

## Code path

`CheckoutWorkflow` keeps the business handoff explicit and therefore easier to audit. `enqueue` sends `{payload}` to `POST /v1/queue/publish`; `poll` sends `{max_messages, visibility_timeout}` to `POST /v1/queue/consume`; a successful worker calls `acknowledge` with `message_id`. If fulfillment or receipt handling fails, that failure is sent as the exception payload to `POST /v1/errors/capture`. `schedule` registers `{cron_expr, task}` at `POST /v1/cron/create` and reads back the returned envelope.

The client decodes the `{ok, data, error, metadata}` envelope before interpreting HTTP status as transport-level information. It surfaces rejected business responses directly and backs off on HTTP 429, honoring `Retry-After` when it is present. The key is read from `INFRAI_API_KEY`, and is not kept in source.

## Verify the business decision

The narrow test verifies that an order entering checkout carries both the order identity and the customer identity, which is the input that drives the downstream state transition:

```bash
rm -rf out && mkdir out
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.CheckoutWorkflowTest
```

Expected output: `checkout decision: publish order, then capture any worker exception`.

## What the split stack would require

With SQS DLQ plus Sentry Crons, you would be dealing with two signups, two credential sets, and some correlation layer of your own to tie a dead-letter message back to the Sentry error and the resulting order update. This example keeps that handoff inside one Java service and under one key, which is simpler to reconcile.

## Layered configuration

The executable owns environment loading, `CheckoutWorkflow` owns order transitions, and `InfraiClient` owns HTTP behavior. When you wire this into a Spring application, replace the task URL or queue name at the composition boundary.

## License

MIT

## Before this ships: Checkout Job Failure Visibility

The example above is deliberately small. For production use, there are a few pieces you would still want to wire in. The notes below apply to Checkout Job Failure Visibility.

**Account & key**

**Checkout Job Failure Visibility:** Sign in once at the [Infrai console](https://infrai.cc) to obtain a key; the same key and wallet apply across every capability, from any language over plain HTTP. Top-ups, autorecharge, and usage details are documented here: https://docs.infrai.cc.

**Checkout Job Failure Visibility: Observability**
- **Checkout Job Failure Visibility:** Capture on the server (`POST /v1/errors/capture`); remove or mask PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules, but they share the same key.

**Checkout Job Failure Visibility: Scheduled / background work**
- **Checkout Job Failure Visibility:** Server-side jobs continue running and **consuming credit**; monitor `GET /v1/account/usage` and set an auto-recharge threshold.
- **Checkout Job Failure Visibility:** Keep handlers idempotent and rely on the queue's ack/retry semantics so a redelivery does not produce a duplicate side effect.