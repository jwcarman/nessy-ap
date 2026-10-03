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

import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a case cost, as a whole: per model, the input, output, cache read, cache write and reasoning
 * counts, read from the desk's projection of Nessy's stored history. A count the vendor never
 * reported is null, never zero: no number is better than a wrong one, and a bare token total would
 * hide what was cached and what was reasoning.
 */
public record Usage(Map<String, Counts> byModel) {

  /** Usage that could not be read at all. */
  public static final Usage UNKNOWN = new Usage(Map.of());

  /** One model's counts; each null when never reported. */
  public record Counts(Long input, Long output, Long cacheRead, Long cacheWrite, Long reasoning) {}

  public Usage {
    byModel = Map.copyOf(byModel);
  }

  /** A case's usage as the desk reports it at {@code /api/cases/{id}/usage}. */
  public static Usage ofCase(JsonNode spent) {
    Map<String, Counts> byModel = new HashMap<>();
    for (JsonNode model : spent.path("byModel")) {
      byModel.put(
          model.path("model").asString(),
          new Counts(
              count(model, "input"),
              count(model, "output"),
              count(model, "cacheRead"),
              count(model, "cacheWrite"),
              count(model, "reasoning")));
    }
    return new Usage(byModel);
  }

  private static Long count(JsonNode model, String kind) {
    JsonNode count = model.path(kind);
    return count.isNumber() ? count.asLong() : null;
  }
}
