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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SettledTest {

  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
  private static final Duration QUIET = Duration.ofSeconds(8);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode view(String status, String decisionStatus, Instant lastEvent) {
    return JSON.readTree(
        """
        {"status": "%s",
         "decisions": [{"status": "%s"}],
         "timeline": [{"at": "2026-10-03T07:00:00Z", "kind": "tool"},
                      {"at": "%s", "kind": "resolved"}]}
        """
            .formatted(status, decisionStatus, lastEvent));
  }

  @Test
  void a_resolved_case_that_has_been_quiet_long_enough_is_settled() {
    assertThat(Settled.of(view("RESOLVED", "ANSWERED", NOW.minusSeconds(9)), NOW, QUIET)).isTrue();
  }

  @Test
  void a_resolved_case_still_busy_is_not() {
    assertThat(Settled.of(view("RESOLVED", "ANSWERED", NOW.minusSeconds(2)), NOW, QUIET)).isFalse();
  }

  @Test
  void a_case_with_a_decision_not_yet_answered_is_not() {
    assertThat(Settled.of(view("RESOLVED", "DECIDED", NOW.minusSeconds(60)), NOW, QUIET)).isFalse();
  }

  @Test
  void a_case_still_investigating_is_not() {
    assertThat(Settled.of(view("INVESTIGATING", "ANSWERED", NOW.minusSeconds(60)), NOW, QUIET))
        .isFalse();
  }
}
