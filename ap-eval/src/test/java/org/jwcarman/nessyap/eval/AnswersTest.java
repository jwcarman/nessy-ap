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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The evaluation's people answer the agent's questions as each scenario scripts. */
class AnswersTest {

  private final JsonMapper json = JsonMapper.builder().build();

  private JsonNode question(String askedOf, String answeredAt) {
    return json.readTree(
        "{\"id\":\"q1\",\"askedOf\":\""
            + askedOf
            + "\",\"answeredAt\":"
            + (answeredAt == null ? "null" : "\"" + answeredAt + "\"")
            + "}");
  }

  @Test
  void the_buyer_answers_with_the_scenarios_words() {
    assertThat(Runner.answerFor(Scenarios.PRICE_VARIANCE_SMALL, question("bob", null)))
        .hasValueSatisfying(words -> assertThat(words).startsWith("Yes, I agreed"));
  }

  @Test
  void a_silent_buyer_and_an_answered_question_get_nothing() {
    assertThat(Runner.answerFor(Scenarios.SILENT_BUYER, question("bob", null))).isEmpty();
    assertThat(
            Runner.answerFor(
                Scenarios.PRICE_VARIANCE_SMALL, question("bob", "2026-10-03T12:00:00Z")))
        .isEmpty();
  }

  @Test
  void nobody_the_evaluation_does_not_play_is_answered_for() {
    assertThat(Runner.answerFor(Scenarios.PRICE_VARIANCE_SMALL, question("betty", null))).isEmpty();
  }

  @Test
  void a_persons_answer_names_the_seeds_po_where_the_scenario_says_so() {
    JsonNode seeded = JsonMapper.builder().build().readTree("{\"poNumber\": \"PO-REAL\"}");

    assertThat(
            Runner.answerFor(Scenarios.named("vendor-names-the-po"), question("bob", null), seeded))
        .hasValueSatisfying(text -> assertThat(text).contains("my order PO-REAL"));
  }
}
