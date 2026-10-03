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
package org.jwcarman.nessyap.eval;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tokens for the dev realm's people, by direct grant through the {@code ap-eval} client. Each
 * user's password is their username: a dev realm only.
 */
final class Keycloak {

  private record Held(String token, Instant expires) {}

  private final HttpClient http = HttpClient.newHttpClient();
  private final String tokenUrl;
  private final JsonMapper json;
  private final Map<String, Held> held = new ConcurrentHashMap<>();

  Keycloak(String realmUrl, JsonMapper json) {
    this.tokenUrl = realmUrl + "/protocol/openid-connect/token";
    this.json = json;
  }

  String tokenFor(String username) {
    Held current = held.get(username);
    if (current != null && current.expires().isAfter(Instant.now().plusSeconds(30))) {
      return current.token();
    }
    String form =
        "client_id=ap-eval&grant_type=password&username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(username, StandardCharsets.UTF_8);
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(tokenUrl))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Keycloak refused " + username + ": " + response.statusCode() + " " + response.body());
      }
      JsonNode body = json.readTree(response.body());
      Held fresh =
          new Held(
              body.path("access_token").asString(),
              Instant.now().plusSeconds(body.path("expires_in").asLong(60)));
      held.put(username, fresh);
      return fresh.token();
    } catch (IOException e) {
      throw new IllegalStateException("cannot reach Keycloak at " + tokenUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
