package com.example.checkout;

/** Checkout -> fulfillment -> receipt -> customer update, with failures retained in the queue. */
public final class CheckoutWorkflow {
  private final InfraiClient infrai;
  private final String queue;

  public CheckoutWorkflow(InfraiClient infrai, String queue) { this.infrai = infrai; this.queue = queue; }

  public String enqueue(String orderId, String customerId) throws Exception {
    String payload = "{\"order_id\":" + InfraiClient.json(orderId) + ",\"customer_id\":" + InfraiClient.json(customerId) + "}";
    return infrai.post("/v1/queue/publish", "{\"queue\":" + InfraiClient.json(queue) + ",\"payload\":" + InfraiClient.json(payload) + "}");
  }

  public String poll(int maxMessages) throws Exception {
    return infrai.post("/v1/queue/consume", "{\"queue\":" + InfraiClient.json(queue) + ",\"max_messages\":" + maxMessages + ",\"visibility_timeout\":30}");
  }

  public String acknowledge(String messageId) throws Exception {
    return infrai.post("/v1/queue/ack", "{\"queue\":" + InfraiClient.json(queue) + ",\"message_id\":" + InfraiClient.json(messageId) + "}");
  }

  public String captureFailure(String orderId, Exception failure) throws Exception {
    String error = "{\"order_id\":" + InfraiClient.json(orderId) + ",\"exception\":" + InfraiClient.json(failure.toString()) + "}";
    return infrai.post("/v1/errors/capture", error);
  }

  public String schedule(String taskUrl) throws Exception {
    return infrai.post("/v1/cron/create", "{\"cron_expr\":\"*/5 * * * *\",\"task\":" + InfraiClient.json(taskUrl) + "}");
  }
}
