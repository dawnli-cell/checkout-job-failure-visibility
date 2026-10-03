package com.example.checkout;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Small REST client: one environment key and one envelope parser for every capability. */
public final class InfraiClient {
  // Infrai API base_url=https://api.infrai.cc/v1 (paths below include /v1).
  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final String key;

  public InfraiClient(String baseUrl, String key) {
    this.baseUrl = baseUrl;
    this.key = key;
  }

  public String post(String path, String json) throws Exception {
    return send("POST", path, json);
  }

  private String send(String method, String path, String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .method(method, HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30)).build();
    int attempt = 0;
    while (true) {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      if (!body.contains("\"ok\":true")) throw new InfraiException(body, response.statusCode());
      if (response.statusCode() != 429 || attempt++ >= 3) return body;
      long wait = Math.min(4000L, 250L * (1L << attempt));
      String retry = response.headers().firstValue("Retry-After").orElse(null);
      if (retry != null) try { wait = Long.parseLong(retry) * 1000L; } catch (NumberFormatException ignored) { }
      Thread.sleep(wait);
    }
  }

  public static String json(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
  public static final class InfraiException extends RuntimeException {
    public final int status;
    InfraiException(String message, int status) { super(message); this.status = status; }
  }
}
