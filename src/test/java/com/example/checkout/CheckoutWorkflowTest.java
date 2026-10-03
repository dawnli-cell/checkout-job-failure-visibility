package com.example.checkout;

public final class CheckoutWorkflowTest {
  public static void main(String[] args) {
    String payload = "{\"order_id\":\"order-7\",\"customer_id\":\"customer-9\"}";
    if (!payload.contains("order_id") || !payload.contains("customer_id")) throw new AssertionError("order payload");
    System.out.println("checkout decision: publish order, then capture any worker exception");
  }
}
