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
    return view(status, decisionStatus, lastEvent, "resolved");
  }

  private static JsonNode view(
      String status, String decisionStatus, Instant lastEvent, String lastKind) {
    return JSON.readTree(
        """
        {"status": "%s",
         "decisions": [{"status": "%s"}],
         "timeline": [{"at": "2026-10-03T07:00:00Z", "kind": "tool"},
                      {"at": "%s", "kind": "%s"}]}
        """
            .formatted(status, decisionStatus, lastEvent, lastKind));
  }

  @Test
  void a_resolved_case_that_has_been_quiet_long_enough_is_settled() {
    assertThat(Settled.of(view("RESOLVED", "ANSWERED", NOW.minusSeconds(9)), NOW, QUIET)).isTrue();
  }

  @Test
  void a_case_put_in_front_of_a_person_that_has_been_quiet_long_enough_is_settled() {
    assertThat(Settled.of(view("NEEDS_PERSON", "ANSWERED", NOW.minusSeconds(9)), NOW, QUIET))
        .isTrue();
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
  void a_case_waiting_on_an_answer_that_has_been_quiet_long_enough_is_settled() {
    assertThat(Settled.of(view("AWAITING_ANSWER", "ANSWERED", NOW.minusSeconds(9)), NOW, QUIET))
        .isTrue();
  }

  @Test
  void a_waiting_case_the_evaluation_just_answered_is_not_settled_until_the_answer_lands() {
    JsonNode waiting = view("AWAITING_ANSWER", "ANSWERED", NOW.minusSeconds(30));

    assertThat(Settled.of(waiting, NOW, QUIET, NOW.minusSeconds(5))).isFalse();
    assertThat(Settled.of(waiting, NOW, QUIET, NOW.minus(Settled.AFTER_REPLY).minusSeconds(1)))
        .isTrue();
  }

  @Test
  void a_case_the_agent_left_investigating_with_nothing_pending_is_settled_as_stalled() {
    JsonNode stalled =
        JSON.readTree(
            """
            {"status": "INVESTIGATING", "decisions": [], "questions": [],
             "timeline": [{"at": "%s", "kind": "note"}]}
            """
                .formatted(NOW.minus(Settled.AFTER_REPLY).minusSeconds(1)));

    assertThat(Settled.of(stalled, NOW, QUIET)).isTrue();
  }

  @Test
  void a_case_the_agent_has_not_touched_yet_is_not_stalled() {
    JsonNode fresh =
        JSON.readTree(
            """
            {"status": "INVESTIGATING", "decisions": [], "questions": [], "timeline": []}
            """);

    assertThat(Settled.of(fresh, NOW, QUIET)).isFalse();
  }

  @Test
  void a_case_still_investigating_is_not() {
    assertThat(Settled.of(view("INVESTIGATING", "ANSWERED", NOW.minusSeconds(60)), NOW, QUIET))
        .isFalse();
  }

  @Test
  void a_reply_the_agent_has_not_acted_on_yet_keeps_the_case_open_longer() {
    // A slow model may take well past the usual quiet period to make its first move on a reply.
    assertThat(
            Settled.of(
                view("RESOLVED", "ANSWERED", NOW.minusSeconds(20), "mail-received"), NOW, QUIET))
        .isFalse();
    assertThat(
            Settled.of(
                view(
                    "RESOLVED",
                    "ANSWERED",
                    NOW.minus(Settled.AFTER_REPLY).minusSeconds(1),
                    "mail-received"),
                NOW,
                QUIET))
        .isTrue();
  }
}
