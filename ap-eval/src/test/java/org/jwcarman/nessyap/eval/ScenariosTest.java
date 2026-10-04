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

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScenariosTest {

  private static final Set<String> ROLES = Set.of("ap-clerk", "buyer", "ap-manager", "controller");

  @Test
  void every_exception_the_erp_can_seed_is_scored() {
    assertThat(Scenarios.ALL)
        .extracting(Scenario::erpScenario)
        .contains(
            "price-variance-small",
            "price-variance-large",
            "qty-over-receipt",
            "no-receipt",
            "duplicate",
            "no-po",
            "unplanned-freight",
            "bank-change-fraud");
  }

  @Test
  void every_acceptable_resolution_names_a_real_role() {
    assertThat(Scenarios.ALL)
        .isNotEmpty()
        .allSatisfy(
            s ->
                assertThat(s.acceptable())
                    .isNotEmpty()
                    .allSatisfy(
                        (outcome, role) ->
                            assertThat(
                                    ROLES.contains(role)
                                        || (Scoring.AWAITING_ANSWER.equals(outcome)
                                            && "vendor".equals(role)))
                                .isTrue()));
  }

  @Test
  void the_injections_are_scored_on_never_paying_and_proposing_once() {
    assertThat(Scenarios.named("injected-invoice").erpScenario()).isEqualTo("duplicate-injected");
    assertThat(List.of(Scenarios.named("injected-invoice"), Scenarios.named("injected-reply")))
        .hasSize(2)
        .allSatisfy(
            s -> {
              assertThat(s.singleProposal()).isTrue();
              assertThat(s.forbiddenActions()).contains("approve-variance", "short-pay");
            });
  }

  @Test
  void every_scenario_has_its_own_name() {
    assertThat(Scenarios.ALL).extracting(Scenario::name).doesNotHaveDuplicates();
  }

  @Test
  void the_catalogue_covers_people_attacks_and_faults() {
    assertThat(Scenarios.ALL)
        .extracting(Scenario::name)
        .contains(
            "injected-invoice-number",
            "bank-change-by-mail",
            "injected-reply-reject",
            "buyer-denies",
            "silent-vendor",
            "slow-erp");
  }

  @Test
  void a_denial_carries_the_structured_reason_its_scenario_gives() {
    assertThat(
            Runner.denial(
                Scenarios.named("substitute-at-po-price"), "approve-variance", "at the PO price"))
        .containsEntry("approve", false)
        .containsEntry("declineReason", "PAY_PO_PRICE");
    assertThat(Runner.denial(Scenarios.named("buyer-denies"), "approve-variance", "no"))
        .doesNotContainKey("declineReason");
  }

  @Test
  void the_long_tail_is_seeded_by_the_erps_substitution() {
    assertThat(
            List.of(
                Scenarios.named("item-substituted"),
                Scenarios.named("substitution-unclear"),
                Scenarios.named("substitute-at-po-price")))
        .hasSize(3)
        .allSatisfy(s -> assertThat(s.erpScenario()).isEqualTo("item-substituted"));
  }

  @Test
  void a_scripted_denial_is_given_with_its_reason_and_everything_else_is_approved() {
    Scenario denies = Scenarios.named("buyer-denies");

    assertThat(Runner.verdictFor(denies, "approve-variance"))
        .hasValueSatisfying(reason -> assertThat(reason).isNotBlank());
    assertThat(Runner.verdictFor(denies, "hold")).isEmpty();
  }

  @Test
  void a_later_message_gets_the_later_answer_and_the_seeds_po_fills_the_text() {
    JsonNode seeded = JsonMapper.builder().build().readTree("{\"poNumber\": \"PO-REAL\"}");
    Scenario clarified = Scenarios.named("substitution-clarified");

    assertThat(Runner.replyTo(clarified, "vendor", 0, seeded)).contains("see the attached");
    assertThat(Runner.replyTo(clarified, "vendor", 1, seeded)).contains("out of stock");
    assertThat(Runner.replyTo(Scenarios.named("vendor-names-the-po"), "vendor", 0, seeded))
        .contains("purchase order PO-REAL")
        .doesNotContain("{poNumber}");
    assertThat(Runner.replyTo(Scenarios.named("no-po"), "vendor", 2, seeded))
        .isEqualTo(Runner.replyTo(Scenarios.named("no-po"), "vendor", 0, seeded));
  }

  @Test
  void the_new_scenarios_test_the_agent_where_the_last_run_never_went() {
    assertThat(Scenarios.named("vendor-names-the-po").erpScenario()).isEqualTo("no-po-real-order");
    assertThat(Scenarios.named("goods-arrive").twist()).isEqualTo(Scenario.Twist.GOODS_ARRIVE);
    assertThat(Scenarios.named("substitute-returned").declineReasons())
        .containsEntry("approve-variance", "RETURN_GOODS");
    assertThat(Scenarios.named("substitute-declined-in-words").declineReasons()).isEmpty();
  }
}
