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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent's own credential for the ERP: a client-credentials token, fetched when needed and kept
 * until shortly before it expires. It reads; it never carries a person's authority.
 */
public class ServiceToken {

  private static final Logger log = LoggerFactory.getLogger(ServiceToken.class);
  private static final Duration EARLY = Duration.ofSeconds(30);

  private record Held(String value, Instant refreshAt) {}

  private final String tokenUri;
  private final String form;
  private final Clock clock;
  private final Duration timeout;
  private final JsonMapper json;
  private final HttpClient http;
  private Held held;

  public ServiceToken(
      String tokenUri,
      String clientId,
      String clientSecret,
      Clock clock,
      Duration timeout,
      JsonMapper json) {
    this.tokenUri = tokenUri;
    this.form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
    this.clock = clock;
    this.timeout = timeout;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /**
   * The current token, fetching a new one if it is missing or about to expire; empty on failure.
   */
  public synchronized Optional<String> current() {
    if (held != null && clock.instant().isBefore(held.refreshAt())) {
      return Optional.of(held.value());
    }
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(tokenUri))
                  .timeout(timeout)
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("The ERP service token was refused: HTTP {}", response.statusCode());
        return Optional.empty();
      }
      JsonNode body = json.readTree(response.body());
      Instant expires = clock.instant().plusSeconds(body.path("expires_in").asLong(60));
      held = new Held(body.path("access_token").asString(), expires.minus(EARLY));
      return Optional.of(held.value());
    } catch (IOException | JacksonException e) {
      log.warn("Could not get an ERP service token: {}", e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
