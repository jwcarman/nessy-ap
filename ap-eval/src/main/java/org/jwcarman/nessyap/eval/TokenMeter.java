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

import tools.jackson.databind.JsonNode;

/**
 * The agent's token spend, read from the metric Nessy publishes ({@code gen_ai.client.token.usage},
 * input plus output). It is aggregated for the whole agent type, so a case's spend is the
 * difference across it: honest only while cases run one at a time, as the evaluation runs them.
 */
final class TokenMeter {

  private static final String METRIC = "/actuator/metrics/gen_ai.client.token.usage";

  private final Http http;
  private final String agentUrl;

  TokenMeter(Http http, String agentUrl) {
    this.http = http;
    this.agentUrl = agentUrl;
  }

  /** Tokens spent so far, or -1 when the metric cannot be read (no call has been made yet is 0). */
  long total() {
    try {
      return sum("input") + sum("output");
    } catch (IllegalStateException e) {
      return -1;
    }
  }

  private long sum(String type) {
    return http.get(agentUrl + METRIC + "?tag=gen_ai.token.type:" + type)
        .map(TokenMeter::totalOf)
        .orElse(0L);
  }

  private static long totalOf(JsonNode metric) {
    for (JsonNode measurement : metric.path("measurements")) {
      if ("TOTAL".equals(measurement.path("statistic").asString())) {
        return Math.round(measurement.path("value").asDouble());
      }
    }
    return 0;
  }
}
