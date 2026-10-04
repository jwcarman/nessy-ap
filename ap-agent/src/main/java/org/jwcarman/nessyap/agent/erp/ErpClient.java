/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessyap.agent.erp;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The ERP's REST API, as the agent and the decision executor see it. */
public class ErpClient {

  private final String baseUrl;
  private final Duration readTimeout;
  private final JsonMapper json;
  private final HttpClient http;
  private final ServiceToken serviceToken;

  public ErpClient(String baseUrl, Duration connectTimeout, Duration readTimeout, JsonMapper json) {
    this(baseUrl, connectTimeout, readTimeout, json, null);
  }

  /**
   * @param serviceToken the agent's own credential, sent on reads; null to read unauthenticated
   */
  public ErpClient(
      String baseUrl,
      Duration connectTimeout,
      Duration readTimeout,
      JsonMapper json,
      ServiceToken serviceToken) {
    this.serviceToken = serviceToken;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.readTimeout = readTimeout;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  public ErpOutcome invoice(UUID invoiceId) {
    return get("/api/invoices/" + invoiceId);
  }

  public ErpOutcome purchaseOrder(String poNumber) {
    return get("/api/purchase-orders/" + segment(poNumber));
  }

  public ErpOutcome receipts(String poNumber) {
    return get("/api/purchase-orders/" + segment(poNumber) + "/receipts");
  }

  public ErpOutcome vendor(UUID vendorId) {
    return get("/api/vendors/" + vendorId);
  }

  public ErpOutcome vendorInvoices(UUID vendorId) {
    return get("/api/vendors/" + vendorId + "/invoices");
  }

  public ErpOutcome matchException(UUID exceptionId) {
    return get("/api/match-exceptions/" + exceptionId);
  }

  public ErpOutcome similarInvoices(UUID vendorId, String invoiceNumber, BigDecimal total) {
    String query = "vendorId=" + vendorId + "&invoiceNumber=" + segment(invoiceNumber);
    if (total != null) {
      query += "&total=" + total.toPlainString();
    }
    return get("/api/invoices/similar?" + query);
  }

  /**
   * Applies a resolution command. The ERP applies a given key at most once, so a retry with the
   * same key is safe.
   */
  public ErpOutcome resolve(
      UUID invoiceId,
      String action,
      String idempotencyKey,
      long expectedVersion,
      BigDecimal amount,
      String comment) {
    return resolve(invoiceId, action, idempotencyKey, expectedVersion, amount, comment, null);
  }

  /**
   * As {@link #resolve(UUID, String, String, long, BigDecimal, String)}, as the person whose token
   * this is.
   */
  public ErpOutcome resolve(
      UUID invoiceId,
      String action,
      String idempotencyKey,
      long expectedVersion,
      BigDecimal amount,
      String comment,
      String bearerToken) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", expectedVersion);
    if (amount != null) {
      body.put("amount", amount);
    }
    if (comment != null) {
      body.put("comment", comment);
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri("/api/invoices/" + invoiceId + "/" + segment(action)))
            .header("Idempotency-Key", idempotencyKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    return send(request);
  }

  /** Records a call to the vendor's contact of record, as the person who made it. */
  public ErpOutcome recordCallBack(
      UUID vendorId, UUID accountId, String phone, boolean vendorConfirmed, String bearerToken) {
    return postAs(
        "/api/vendors/" + vendorId + "/bank-changes/" + accountId + "/call-back",
        Map.of("phone", phone, "vendorConfirmed", vendorConfirmed),
        bearerToken);
  }

  /** Confirms a called-back change, as a second person. */
  public ErpOutcome confirmBankChange(UUID vendorId, UUID accountId, String bearerToken) {
    return postAs(
        "/api/vendors/" + vendorId + "/bank-changes/" + accountId + "/confirm",
        Map.of(),
        bearerToken);
  }

  private ErpOutcome postAs(String path, Object body, String bearerToken) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    return send(request);
  }

  private ErpOutcome get(String path) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
    if (serviceToken != null) {
      var token = serviceToken.current();
      if (token.isEmpty()) {
        return new ErpOutcome.Unavailable("no service token for the ERP");
      }
      request.header("Authorization", "Bearer " + token.get());
    }
    return send(request);
  }

  private ErpOutcome send(HttpRequest.Builder request) {
    HttpResponse<String> response;
    try {
      response =
          http.send(
              request.timeout(readTimeout).header("Accept", "application/json").build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      return new ErpOutcome.Unavailable(describe(e));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return new ErpOutcome.Unavailable("interrupted");
    }
    int status = response.statusCode();
    if (status == 429) {
      // A rate limit is a wait, never a refusal: say how long the ERP asked for.
      String wait = response.headers().firstValue("Retry-After").orElse("a few");
      return new ErpOutcome.Unavailable(
          "rate limited by the ERP; retry after " + wait + " seconds");
    }
    if (status >= 500) {
      return new ErpOutcome.Unavailable("HTTP " + status + " " + detailOf(response.body()));
    }
    if (status >= 400) {
      return refusal(status, response.body());
    }
    try {
      return new ErpOutcome.Ok(json.readTree(response.body()));
    } catch (JacksonException e) {
      return new ErpOutcome.Unavailable("unreadable ERP response: " + e.getOriginalMessage());
    }
  }

  private ErpOutcome refusal(int status, String body) {
    try {
      JsonNode problem = json.readTree(body);
      if (problem.isObject() && problem.hasNonNull("code")) {
        return new ErpOutcome.Refused(
            status, problem.get("code").asString(), problem.path("detail").asString(""));
      }
    } catch (JacksonException _) {
      // not a problem document; fall through to the bare status
    }
    return new ErpOutcome.Refused(status, "HTTP_" + status, body);
  }

  private String detailOf(String body) {
    try {
      JsonNode problem = json.readTree(body);
      return problem.path("code").asString(problem.path("detail").asString(""));
    } catch (JacksonException _) {
      return "";
    }
  }

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String describe(IOException e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
