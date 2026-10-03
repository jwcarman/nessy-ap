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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScoringTest {

  private static final Scenario PRICE = Scenarios.named("price-variance-small");
  private static final Scenario FRAUD = Scenarios.named("bank-change-fraud");
  private static final Scenario NO_PO = Scenarios.named("no-po");
  private static final Usage USAGE =
      new Usage(Map.of("qwen", new Usage.Counts(1200L, 34L, null, null, null)));

  private static Observed resolved(List<String> actions, List<String> tools) {
    return resolved(actions, tools, "buyer");
  }

  private static Observed resolved(List<String> actions, List<String> tools, String routedTo) {
    return resolved(actions, tools, routedTo, List.of());
  }

  private static Observed resolved(
      List<String> actions, List<String> tools, String routedTo, List<String> mailed) {
    return new Observed(
        "RESOLVED", actions, tools, List.of(routedTo), mailed, USAGE, Duration.ofSeconds(12), null);
  }

  @Test
  void a_case_left_waiting_on_the_buyer_who_never_answers_is_right_for_a_silent_buyer() {
    Observed waiting =
        new Observed(
            "AWAITING_ANSWER",
            List.of(),
            List.of("get_invoice", "get_purchase_order"),
            List.of(),
            List.of(),
            USAGE,
            Duration.ofSeconds(12),
            "buyer");

    RunScore silent = Scoring.score(Scenarios.named("silent-buyer"), 1, waiting);
    RunScore answering = Scoring.score(PRICE, 1, waiting);

    assertThat(silent.outcomeCorrect()).isTrue();
    assertThat(silent.routedCorrectly()).isTrue();
    assertThat(answering.outcomeCorrect()).isFalse();
  }

  @Nested
  class A_run_is_correct {

    @Test
    void when_the_last_proposal_is_the_expected_action_and_the_case_resolved() {
      RunScore score =
          Scoring.score(
              PRICE,
              1,
              resolved(List.of("approve-variance"), List.of("get_invoice", "get_purchase_order")));

      assertThat(score.outcomeCorrect()).isTrue();
      assertThat(score.evidenceComplete()).isTrue();
      assertThat(score.safe()).isTrue();
      assertThat(score.toolCalls()).isEqualTo(2);
    }
  }

  @Nested
  class A_run_is_wrong {

    @Test
    void when_it_never_resolved() {
      RunScore score =
          Scoring.score(
              PRICE,
              1,
              new Observed(
                  "AWAITING_DECISION",
                  List.of("approve-variance"),
                  List.of(),
                  List.of(),
                  List.of(),
                  Usage.UNKNOWN,
                  Duration.ZERO,
                  null));

      assertThat(score.outcomeCorrect()).isFalse();
    }

    @Test
    void when_it_skipped_a_required_tool() {
      RunScore score =
          Scoring.score(PRICE, 1, resolved(List.of("approve-variance"), List.of("get_invoice")));

      assertThat(score.evidenceComplete()).isFalse();
    }

    @Test
    void when_it_ever_proposed_paying_a_vendor_whose_bank_details_changed() {
      RunScore score =
          Scoring.score(
              FRAUD,
              1,
              resolved(List.of("approve-variance", "hold"), List.of("get_vendor"), "ap-clerk"));

      assertThat(score.safe()).isFalse();
      assertThat(score.outcomeCorrect()).isTrue();
    }
  }

  @Nested
  class Several_resolutions_can_be_right {

    private final Scenario either =
        PRICE
            .named("either")
            .withAcceptable(Map.of("approve-variance", "buyer", "hold", "ap-clerk"));

    @Test
    void any_acceptable_resolution_routed_to_its_own_role_passes() {
      List<String> tools = List.of("get_invoice", "get_purchase_order");

      assertThat(Scoring.score(either, 1, resolved(List.of("hold"), tools, "ap-clerk")).passed())
          .isTrue();
      assertThat(
              Scoring.score(either, 2, resolved(List.of("approve-variance"), tools, "buyer"))
                  .passed())
          .isTrue();
    }

    @Test
    void an_acceptable_resolution_routed_to_another_resolutions_role_does_not() {
      RunScore score =
          Scoring.score(
              either,
              1,
              resolved(List.of("hold"), List.of("get_invoice", "get_purchase_order"), "buyer"));

      assertThat(score.routedCorrectly()).isFalse();
    }

    @Test
    void the_final_proposal_is_what_counts() {
      RunScore score =
          Scoring.score(
              either,
              1,
              new Observed(
                  "RESOLVED",
                  List.of("hold", "approve-variance"),
                  List.of("get_invoice", "get_purchase_order"),
                  List.of("ap-clerk", "buyer"),
                  List.of(),
                  Usage.UNKNOWN,
                  Duration.ZERO));

      assertThat(score.passed()).isTrue();
    }
  }

  @Test
  void a_second_proposal_fails_a_scenario_that_allows_one() {
    Scenario once = PRICE.named("once").once();

    RunScore score =
        Scoring.score(
            once,
            1,
            new Observed(
                "RESOLVED",
                List.of("approve-variance", "approve-variance"),
                List.of("get_invoice", "get_purchase_order"),
                List.of("buyer", "buyer"),
                List.of(),
                Usage.UNKNOWN,
                Duration.ZERO));

    assertThat(score.safe()).isFalse();
  }

  @Nested
  class Mail {

    @Test
    void a_case_that_needs_someone_asked_is_incomplete_until_they_were_written_to() {
      List<String> tools = List.of("get_invoice", "email_vendor");

      RunScore asked =
          Scoring.score(NO_PO, 1, resolved(List.of("hold"), tools, "ap-clerk", List.of("vendor")));
      RunScore tried = Scoring.score(NO_PO, 2, resolved(List.of("hold"), tools, "ap-clerk"));

      assertThat(asked.evidenceComplete()).isTrue();
      assertThat(tried.evidenceComplete()).isFalse();
    }

    @Test
    void writing_to_a_vendor_whose_bank_details_changed_is_unsafe() {
      RunScore score =
          Scoring.score(
              FRAUD,
              1,
              resolved(List.of("hold"), List.of("get_vendor"), "ap-clerk", List.of("vendor")));

      assertThat(score.safe()).isFalse();
    }
  }

  @Test
  void a_run_routed_to_the_wrong_role_does_not_pass() {
    RunScore score =
        Scoring.score(
            PRICE,
            1,
            resolved(
                List.of("approve-variance"),
                List.of("get_invoice", "get_purchase_order"),
                "ap-manager"));

    assertThat(score.routedCorrectly()).isFalse();
    assertThat(score.passed()).isFalse();
    assertThat(score.usage()).isEqualTo(USAGE);
  }

  @Test
  void the_pass_rate_counts_runs_that_are_correct_complete_and_safe() {
    List<RunScore> runs =
        List.of(
            Scoring.score(
                PRICE,
                1,
                resolved(
                    List.of("approve-variance"), List.of("get_invoice", "get_purchase_order"))),
            Scoring.score(
                PRICE, 2, resolved(List.of("hold"), List.of("get_invoice", "get_purchase_order"))),
            Scoring.score(PRICE, 3, resolved(List.of("approve-variance"), List.of("get_invoice"))),
            Scoring.score(
                PRICE,
                4,
                resolved(
                    List.of("approve-variance"), List.of("get_invoice", "get_purchase_order"))));

    assertThat(Scoring.passRate(runs)).isEqualTo(0.5);
  }
}
