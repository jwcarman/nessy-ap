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
package org.jwcarman.nessyap.agent.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The resolver's decision tables, row by row. No model, no Spring, no container: the same slots
 * give the same outcome every time.
 */
class ResolverTest {

  private static final Resolver RESOLVER = Resolver.fromClasspath(Resolver.TABLES);

  private static Map<String, Object> slots(Object... pairs) {
    Map<String, Object> slots = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      slots.put((String) pairs[i], pairs[i + 1]);
    }
    return slots;
  }

  private static Outcome.Resolved resolved(Map<String, Object> slots) {
    Outcome outcome = RESOLVER.resolve(slots);
    assertThat(outcome).isInstanceOf(Outcome.Resolved.class);
    return (Outcome.Resolved) outcome;
  }

  @Nested
  class Cases_the_erp_facts_settle {

    @Test
    void a_small_price_variance_is_approved_by_the_buyer() {
      assertThat(
              resolved(
                      slots("reasonCode", "PRICE_VARIANCE", "variancePercent", new BigDecimal("4")))
                  .action())
          .isEqualTo("approve-variance");
    }

    @Test
    void a_large_price_variance_asks_for_a_credit_memo() {
      assertThat(
              resolved(
                      slots(
                          "reasonCode", "PRICE_VARIANCE", "variancePercent", new BigDecimal("16")))
                  .action())
          .isEqualTo("request-credit-memo");
    }

    @Test
    void a_declined_variance_asks_for_a_credit_memo() {
      assertThat(
              resolved(
                      slots(
                          "reasonCode", "PRICE_VARIANCE",
                          "variancePercent", new BigDecimal("4"),
                          "declinedAction", "approve-variance"))
                  .action())
          .isEqualTo("request-credit-memo");
    }

    @Test
    void more_billed_than_received_is_held() {
      assertThat(resolved(slots("reasonCode", "QTY_OVER_RECEIPT")).action()).isEqualTo("hold");
    }

    @Test
    void nothing_received_is_held() {
      assertThat(resolved(slots("reasonCode", "NO_RECEIPT")).action()).isEqualTo("hold");
    }

    @Test
    void a_duplicate_with_its_original_found_is_rejected() {
      assertThat(resolved(slots("reasonCode", "DUPLICATE", "originalFound", true)).action())
          .isEqualTo("reject");
    }

    @Test
    void two_real_shipments_billed_alike_go_to_the_controller_to_pay() {
      assertThat(
              resolved(slots("reasonCode", "POSSIBLE_DUPLICATE", "receiptsCoverBoth", true))
                  .action())
          .isEqualTo("approve-variance");
    }

    @Test
    void a_possible_duplicate_with_one_delivery_is_rejected() {
      assertThat(
              resolved(slots("reasonCode", "POSSIBLE_DUPLICATE", "receiptsCoverBoth", false))
                  .action())
          .isEqualTo("reject");
    }

    @Test
    void an_unplanned_charge_is_short_paid_without_it() {
      Outcome.Resolved outcome = resolved(slots("reasonCode", "UNPLANNED_CHARGE"));

      assertThat(outcome.action()).isEqualTo("short-pay");
      assertThat(outcome.amountBasis()).isEqualTo("WITHOUT_CHARGE");
    }

    @Test
    void a_vendor_with_an_unverified_bank_change_is_held() {
      assertThat(resolved(slots("reasonCode", "VENDOR_BANK_CHANGED")).action()).isEqualTo("hold");
    }
  }

  @Nested
  class A_substituted_item {

    @Test
    void with_no_reason_yet_needs_the_vendor_to_say_why() {
      assertThat(RESOLVER.resolve(slots("reasonCode", "ITEM_SUBSTITUTED")))
          .isEqualTo(new Outcome.NeedsFact("substitutionReason", "vendor"));
    }

    @Test
    void with_a_reason_is_offered_to_the_buyer_to_approve() {
      assertThat(
              resolved(
                      slots("reasonCode", "ITEM_SUBSTITUTED", "substitutionReason", "OUT_OF_STOCK"))
                  .action())
          .isEqualTo("approve-variance");
    }

    @Test
    void declined_to_pay_the_po_price_is_short_paid_to_it() {
      Outcome.Resolved outcome =
          resolved(
              slots(
                  "reasonCode", "ITEM_SUBSTITUTED",
                  "substitutionReason", "OUT_OF_STOCK",
                  "declinedAction", "approve-variance",
                  "declineReason", "PAY_PO_PRICE"));

      assertThat(outcome.action()).isEqualTo("short-pay");
      assertThat(outcome.amountBasis()).isEqualTo("PO_PRICE");
    }

    @Test
    void declined_to_return_the_goods_asks_for_a_credit_memo() {
      assertThat(
              resolved(
                      slots(
                          "reasonCode", "ITEM_SUBSTITUTED",
                          "substitutionReason", "OUT_OF_STOCK",
                          "declinedAction", "approve-variance",
                          "declineReason", "RETURN_GOODS"))
                  .action())
          .isEqualTo("request-credit-memo");
    }
  }

  @Nested
  class When_the_rules_cannot_converge {

    @Test
    void a_case_no_row_covers_escalates_as_unhandled() {
      assertThat(RESOLVER.resolve(slots("reasonCode", "NO_PO")))
          .isEqualTo(new Outcome.Escalate("unhandled"));
    }

    @Test
    void a_declined_hold_is_not_covered_and_escalates() {
      assertThat(RESOLVER.resolve(slots("reasonCode", "NO_RECEIPT", "declinedAction", "hold")))
          .isEqualTo(new Outcome.Escalate("unhandled"));
    }

    @Test
    void two_rows_that_disagree_escalate_as_a_conflict() {
      Resolver overlapping = Resolver.fromClasspath("decisions-test/overlap.dmn");

      assertThat(overlapping.resolve(slots("reasonCode", "PRICE_VARIANCE")))
          .isEqualTo(new Outcome.Escalate("conflict"));
    }
  }
}
