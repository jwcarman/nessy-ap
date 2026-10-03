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

import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Whether a case is finished for scoring: resolved, every decision answered, and the agent quiet
 * for a while since the last thing on its timeline. A case can resolve with a hold and then read a
 * reply that changes its mind; the quiet period lets that happen before the run is judged.
 */
final class Settled {

  private Settled() {}

  static boolean of(JsonNode view, Instant now, Duration quiet) {
    if (!"RESOLVED".equals(view.path("status").asString())) {
      return false;
    }
    for (JsonNode decision : view.path("decisions")) {
      if (!"ANSWERED".equals(decision.path("status").asString())) {
        return false;
      }
    }
    Instant last = Instant.EPOCH;
    for (JsonNode event : view.path("timeline")) {
      Instant at = Instant.parse(event.path("at").asString());
      if (at.isAfter(last)) {
        last = at;
      }
    }
    return !last.plus(quiet).isAfter(now);
  }
}
