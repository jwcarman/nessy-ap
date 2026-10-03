/*
 * Copyright © ${year} James Carman
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in ERP: canned responses per method and path, every request recorded. A real HTTP server
 * on a free port, so the client under test does real HTTP.
 */
public final class ErpStub implements AutoCloseable {

  /** One request as the stub saw it. {@code target} is the path plus any query string. */
  public record Seen(String method, String target, Map<String, List<String>> headers, String body) {

    public String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .map(e -> e.getValue().getFirst())
          .findFirst()
          .orElse(null);
    }
  }

  private record Canned(int status, String body, Duration delay) {}

  private final HttpServer server;
  private final Map<String, Canned> responses = new ConcurrentHashMap<>();
  private final Map<String, Canned> prefixes = new ConcurrentHashMap<>();
  private final List<Seen> seen = new CopyOnWriteArrayList<>();

  public ErpStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start the ERP stub", e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Answer {@code method target} (path plus query, exactly as sent) with this status and body. */
  public ErpStub on(String method, String target, int status, String body) {
    return on(method, target, status, body, Duration.ZERO);
  }

  public ErpStub on(String method, String target, int status, String body, Duration delay) {
    responses.put(method + " " + target, new Canned(status, body, delay));
    return this;
  }

  /**
   * Answer any {@code method} whose target starts with {@code prefix}, unless an exact one does.
   */
  public ErpStub onPrefix(String method, String prefix, int status, String body) {
    prefixes.put(method + " " + prefix, new Canned(status, body, Duration.ZERO));
    return this;
  }

  public List<Seen> seen() {
    return List.copyOf(seen);
  }

  public void reset() {
    responses.clear();
    prefixes.clear();
    seen.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String target = exchange.getRequestURI().getRawPath();
    if (exchange.getRequestURI().getRawQuery() != null) {
      target += "?" + exchange.getRequestURI().getRawQuery();
    }
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    seen.add(
        new Seen(
            exchange.getRequestMethod(), target, Map.copyOf(exchange.getRequestHeaders()), body));
    String key = exchange.getRequestMethod() + " " + target;
    Canned canned =
        responses.getOrDefault(
            key,
            new Canned(
                404,
                "{\"status\":404,\"code\":\"NOT_FOUND\",\"detail\":\"stub has nothing for "
                    + target
                    + "\"}",
                Duration.ZERO));
    if (!responses.containsKey(key)) {
      canned =
          prefixes.entrySet().stream()
              .filter(e -> key.startsWith(e.getKey()))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElse(canned);
    }
    if (!canned.delay().isZero()) {
      try {
        Thread.sleep(canned.delay());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] bytes = canned.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(canned.status(), bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
