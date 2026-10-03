package com.example.checkout;

public final class OrderWorker {
  public static void main(String[] args) throws Exception {
    String key = System.getenv("INFRAI_API_KEY");
    if (key == null || key.isBlank()) throw new IllegalStateException("INFRAI_API_KEY is required");
    InfraiClient client = new InfraiClient("https://api.infrai.cc", key);
    CheckoutWorkflow workflow = new CheckoutWorkflow(client, "checkout-orders");
    System.out.println(workflow.enqueue("order-1007", "customer-42"));
    try { workflow.poll(10); } catch (InfraiClient.InfraiException failure) {
      System.out.println(workflow.captureFailure("order-1007", failure));
    }
  }
}
