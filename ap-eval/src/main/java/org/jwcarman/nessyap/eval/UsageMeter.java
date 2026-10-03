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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Reads the agent's running usage totals from the metric Nessy publishes ({@code
 * gen_ai.client.token.usage}, split by {@code gen_ai.token.type} and tagged with the model). It is
 * aggregated for the whole agent type, so a case's usage is the difference across it: honest only
 * while cases run one at a time, as the evaluation runs them.
 */
final class UsageMeter {

  private static final String METRIC = "/actuator/metrics/gen_ai.client.token.usage";
  private static final String MODEL = "gen_ai.request.model";

  private final Http http;
  private final String agentUrl;

  UsageMeter(Http http, String agentUrl) {
    this.http = http;
    this.agentUrl = agentUrl;
  }

  /** The running totals now, or {@link Usage#UNKNOWN} when the metric cannot be read. */
  Usage read() {
    try {
      Map<String, Usage.Counts> byModel = new HashMap<>();
      for (String model : models()) {
        byModel.put(
            model,
            new Usage.Counts(
                total(model, "input"),
                total(model, "output"),
                total(model, "cache_read"),
                total(model, "cache_write"),
                total(model, "reasoning")));
      }
      return new Usage(byModel);
    } catch (IllegalStateException e) {
      return Usage.UNKNOWN;
    }
  }

  private Iterable<String> models() {
    List<String> models = new ArrayList<>();
    http.get(agentUrl + METRIC)
        .ifPresent(
            metric -> {
              for (JsonNode tag : metric.path("availableTags")) {
                if (MODEL.equals(tag.path("tag").asString())) {
                  tag.path("values").forEach(v -> models.add(v.asString()));
                }
              }
            });
    return models;
  }

  /** The running total of one kind for one model; null when no sample of it was ever taken. */
  private Long total(String model, String type) {
    return http.get(
            agentUrl + METRIC + "?tag=" + MODEL + ":" + model + "&tag=gen_ai.token.type:" + type)
        .map(UsageMeter::totalOf)
        .orElse(null);
  }

  private static Long totalOf(JsonNode metric) {
    for (JsonNode measurement : metric.path("measurements")) {
      if ("TOTAL".equals(measurement.path("statistic").asString())) {
        return Math.round(measurement.path("value").asDouble());
      }
    }
    return null;
  }
}
