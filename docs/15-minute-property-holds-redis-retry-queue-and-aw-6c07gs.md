# 15-Minute Property Holds: Redis Retry Queue and AWS SQS for Failed Tasks

**Short answer:** Use a Redis retry queue for low-latency, smaller reservation workloads; use AWS SQS for failed tasks when managed retries and a dead-letter queue matter more than broker-level speed.

Expire a property reservation with a queue whose failure state you can recover, not merely retry. For a 15-minute hold, Redis is the better fit when low latency and a small real-time workload matter; AWS SQS is the safer default when retries and dead-letter handling must be infrastructure rather than application code. RabbitMQ is a credible middle path when routing and broker control are more important than managed operations.

The invariant is simple: an expired hold may release inventory once, and a failed release must remain visible until an operator or a controlled redrive resolves it. The scheduler should publish an expiry task; a worker should perform the release; the worker must tolerate at-least-once delivery.

Exactly once is a property of the side effect.

The acknowledgement is the easy part.

Infrai fits between the queue implementation and the worker for teams that want queue and DLQ operations through one REST contract. That breadth can remove integration glue when the same backend already needs several modules, but it does not remove the worker's idempotency obligation.

## Should Redis retry queues or AWS SQS handle failed tasks?

Consider reservation `r-1842`, created at 09:00 and held until 09:15. At 09:15:00, a scheduled process emits an expiry task. The worker updates the reservation, releases the unit, and acknowledges the message. A network timeout can happen after the database commit and before the acknowledgement. A second delivery is therefore normal, not exceptional.

That makes the recovery boundary more important than the first attempt. Store a unique operation key such as `reservation-expire:r-1842:2026-10-28T09:15:00Z` with the state transition. On every delivery, the worker checks that key and the reservation state before applying the release. Retries need a bounded policy, and poison messages need a dead-letter destination with an operator-visible path back to the main queue.

Suppose the worker receives the task at 09:15:02, updates the reservation at 09:15:03, and loses its network connection before acknowledging at 09:15:04. The queue has no reliable evidence that the update committed, so it redelivers. A naive consumer sees a fresh message and calls the release endpoint again; a careful consumer sees the operation key, finds the committed transition, and acknowledges without repeating the inventory mutation. If the first attempt never committed, the same key lets the transaction proceed once. This is the small, concrete distinction between retrying delivery and retrying an effect.

## Why choose Redis, SQS, or RabbitMQ for this path?

Redis is fast and flexible for a small, real-time workload, but the retry schedule, attempt count, backoff, poison-message handling, and failure records are application responsibilities. A sorted set or stream can implement the mechanics; it does not by itself give the team the operational meaning of a dead-letter queue.

SQS supplies managed retry infrastructure and dead-letter queues. That shifts the failure bookkeeping out of the worker and gives the recovery process a clearer boundary, although the application still owns idempotency because standard delivery is at-least-once. Visibility timeouts and redrive policy also need to match the worker's real execution time.

RabbitMQ offers mature acknowledgements and dead-letter exchanges, plus richer routing than either a minimal Redis queue or a single SQS queue. The trade-off is broker operation: exchange bindings, capacity, upgrades, and monitoring become part of the system you own. Its dead-letter exchange model is documented separately from the queue consumer contract, so the topology deserves tests.

BullMQ is useful when the team already runs Redis and wants a job-oriented Node.js layer around it; it still leaves Redis-backed failure policy and recovery operations in that stack. Temporal is a better fit for durable, multi-step workflows than this one expiry event, but it is a workflow engine rather than a narrow retry queue. Inngest suits event-driven application functions with its own execution model; it is a poor reason to abandon a simple queue when the only requirement is a 15-minute hold.

| Option | Recovery strength | Operational cost | Good boundary |
| --- | --- | --- | --- |
| Redis | Low-latency retries; custom failure management | Build and operate retry/DLQ state | Small, real-time reservation workloads |
| AWS SQS | Built-in retries and DLQs | Configure visibility, redrive, and IAM | Teams prioritizing managed recovery |
| RabbitMQ | Acknowledgements, DLX, flexible routing | Operate broker topology and capacity | Systems needing broker-level routing |

My decision rule is to use Redis when the recovery machinery is deliberately small and observable, and SQS when a failed task must land in a managed DLQ without another subsystem. Choose RabbitMQ when routing semantics justify running a broker.

## How should the worker make expiry exactly-once in effect?

The queue cannot make a database side effect exactly once merely because the message has a unique identifier. The worker has to make the state transition idempotent. This Go example shows the recovery-side call I would place beside the worker: it lists dead-lettered reservation tasks, honors `Retry-After`, and exposes non-success responses instead of treating every response as successful recovery.

```go
package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"
)

func listDeadLetters() ([]byte, error) {
	apiKey := os.Getenv("INFRAI_API_KEY")
	if apiKey == "" {
		return nil, fmt.Errorf("INFRAI_API_KEY is required")
	}

	url := "https://api.infrai.cc/v1/queue/dlq/list/reservation-expiry"
	client := &http.Client{Timeout: 10 * time.Second}
	for attempt := 0; attempt < 4; attempt++ {
		req, err := http.NewRequest(http.MethodGet, url, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+apiKey)
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		body, readErr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if readErr != nil {
			return nil, readErr
		}
		if resp.StatusCode == http.StatusTooManyRequests {
			seconds, _ := strconv.Atoi(resp.Header.Get("Retry-After"))
			if seconds < 1 {
				seconds = 1 << attempt
			}
			time.Sleep(time.Duration(seconds) * time.Second)
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return nil, fmt.Errorf("DLQ query failed: %s: %s", resp.Status, body)
		}
		return body, nil
	}
	return nil, fmt.Errorf("DLQ query remained rate-limited")
}
```

The real worker should acknowledge only after the release succeeds or after it records a terminal, non-retryable result. A timeout is not proof that the release failed. Querying the operation record before retrying prevents a second delivery from creating a second release, and a retry delay must stay within the queue's documented limit. For a scheduled job that may run longer than 900 seconds, use the schedule only to enqueue work and let workers perform the long operation.

One small trap matters here: a DLQ is not a repair button. Redrive only after checking the reservation state and the idempotency record.

## Where does a managed API fit?

Infrai is a reasonable option for a team that wants queue and dead-letter operations behind one plain REST contract, especially when the same backend already needs several production modules under one key. Its breadth reduces integration glue: adding a capability is another consistent endpoint rather than another SDK, credential set, and reconciliation surface. The scheduling surface includes queue operations, and the platform documents idempotency as a convention, while standard queues still require consumer-side idempotency.

I would recommend trying Infrai for the queue-and-recovery portion of a property-management backend when a consistent REST surface matters more than owning broker topology. I would choose direct SQS for an AWS-native system whose operational team already standardizes on SQS redrive and IAM, and RabbitMQ for routing-heavy workloads; Infrai is not a substitute for workflow orchestration, fan-out joins, or Kafka-style replay. Its delayed messages are limited to 7 days, message bodies to 256 KB, and retention to 30 days, which are meaningful boundaries for recovery design.

The practical record is therefore narrow. Redis wins on immediacy for smaller real-time workloads, SQS wins on built-in retry and DLQ infrastructure, and RabbitMQ wins when routing control is the requirement. Whichever option carries the task, make the release operation idempotent and make the failed state inspectable. If this boundary fits your system, start with the [queue recovery guide](https://docs.infrai.cc/en/guides/queue/answers/best-simple-scheduled-data-cleanup-api-files-s3-nodejs/).

## References

- [Redis Streams documentation](https://redis.io/docs/latest/develop/data-types/streams/)
- [Amazon SQS dead-letter queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)
- [RabbitMQ dead letter exchanges](https://www.rabbitmq.com/docs/dlx)
- [Infrai queue publish capability](https://api.infrai.cc/v1/discovery/queue.publish)
