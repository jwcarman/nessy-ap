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
package org.jwcarman.nessyap.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Just enough HTTP to drive the two apps. */
final class Http {

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json;

  Http(JsonMapper json) {
    this.json = json;
  }

  JsonNode post(String url) {
    return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()))
        .orElseThrow(() -> new IllegalStateException("POST " + url + " found nothing"));
  }

  Optional<JsonNode> get(String url) {
    return send(HttpRequest.newBuilder(URI.create(url)).GET());
  }

  private Optional<JsonNode> send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          client.send(
              request.timeout(Duration.ofSeconds(30)).build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        return Optional.empty();
      }
      if (response.statusCode() >= 300) {
        throw new IllegalStateException(
            "HTTP "
                + response.statusCode()
                + " from "
                + request.build().uri()
                + ": "
                + response.body());
      }
      return response.body().isBlank()
          ? Optional.of(json.createObjectNode())
          : Optional.of(json.readTree(response.body()));
    } catch (IOException e) {
      throw new IllegalStateException("cannot reach " + request.build().uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
