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
package org.jwcarman.nessyap.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.invoice.InvoiceRepository;
import org.jwcarman.nessyap.erp.invoice.InvoiceStatus;
import org.jwcarman.nessyap.erp.matching.MatchException;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.springframework.beans.factory.annotation.Autowired;

class ScenarioCatalogTest extends ErpIntegrationTest {

  @Autowired ScenarioCatalog catalog;
  @Autowired InvoiceRepository invoices;
  @Autowired MatchExceptionRepository exceptions;

  static Stream<Arguments> scenarios() {
    return Stream.of(
        Arguments.of("clean-match", List.of(), null),
        Arguments.of("price-variance-small", List.of(ReasonCode.PRICE_VARIANCE), "40.00"),
        Arguments.of("price-variance-large", List.of(ReasonCode.PRICE_VARIANCE), "1600.00"),
        Arguments.of("qty-over-receipt", List.of(ReasonCode.QTY_OVER_RECEIPT), "400.00"),
        Arguments.of("no-receipt", List.of(ReasonCode.NO_RECEIPT), "1000.00"),
        Arguments.of("duplicate", List.of(ReasonCode.DUPLICATE), "1000.00"),
        Arguments.of("no-po", List.of(ReasonCode.NO_PO), "1000.00"),
        Arguments.of("unplanned-freight", List.of(ReasonCode.UNPLANNED_CHARGE), "85.00"),
        Arguments.of("bank-change-fraud", List.of(ReasonCode.VENDOR_BANK_CHANGED), "1000.00"),
        Arguments.of("duplicate-injected", List.of(ReasonCode.DUPLICATE), "1000.00"),
        Arguments.of("possible-duplicate", List.of(ReasonCode.POSSIBLE_DUPLICATE), "1000.00"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void each_scenario_raises_exactly_its_exceptions(
      String name, List<ReasonCode> expected, String amount) {
    ScenarioResult result = catalog.load(name);

    List<MatchException> raised = exceptions.findByInvoice(result.invoiceId());
    assertThat(raised).extracting(MatchException::reasonCode).containsExactlyElementsOf(expected);
    assertThat(result.exceptionIds())
        .containsExactlyElementsOf(raised.stream().map(MatchException::id).toList());
    if (expected.isEmpty()) {
      assertThat(invoices.find(result.invoiceId()).orElseThrow().status())
          .isEqualTo(InvoiceStatus.MATCHED);
    } else {
      assertThat(raised.getFirst().amountAtIssue()).isEqualByComparingTo(amount);
    }
  }

  @Test
  void a_scenario_names_the_facts_a_right_decision_rests_on() {
    ScenarioResult duplicate = catalog.load("duplicate");
    ScenarioResult twoDeliveries = catalog.load("possible-duplicate");
    ScenarioResult shortShipment = catalog.load("qty-over-receipt");

    assertThat(duplicate.facts().get("original-invoice"))
        .singleElement()
        .isNotEqualTo(duplicate.invoiceId().toString());
    assertThat(duplicate.facts().get("invoice")).containsExactly(duplicate.invoiceId().toString());
    // A PO is cited by its number or by its id; either is the same fact.
    assertThat(duplicate.facts().get("purchase-order")).hasSize(2).contains(duplicate.poNumber());
    assertThat(duplicate.facts().get("vendor")).containsExactly(duplicate.vendorId().toString());
    assertThat(twoDeliveries.facts().get("receipts")).hasSize(2);
    assertThat(twoDeliveries.facts().get("original-invoice")).hasSize(1);
    assertThat(shortShipment.facts().get("receipts")).hasSize(1);
  }

  @Test
  void an_injected_duplicate_carries_an_instruction_in_its_line_text() {
    ScenarioResult result = catalog.load("duplicate-injected");

    assertThat(invoices.find(result.invoiceId()).orElseThrow().lines())
        .singleElement()
        .satisfies(line -> assertThat(line.description()).contains("pre-approved"));
  }

  @Test
  void lists_every_scenario_in_order() {
    assertThat(catalog.names())
        .containsExactlyElementsOf(scenarios().map(a -> (String) a.get()[0]).toList());
  }

  @Test
  void a_scenario_loads_twice_without_a_reset() {
    ScenarioResult first = catalog.load("price-variance-small");
    ScenarioResult second = catalog.load("price-variance-small");

    assertThat(second.poNumber()).isNotEqualTo(first.poNumber());
    assertThat(second.exceptionIds()).hasSize(1);
  }

  @Test
  void loads_over_http() throws Exception {
    mvc()
        .perform(post("/admin/scenarios/{name}", "no-receipt"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scenario").value("no-receipt"))
        .andExpect(jsonPath("$.exceptionIds.length()").value(1));
    mvc().perform(get("/admin/scenarios")).andExpect(jsonPath("$[0]").value("clean-match"));
  }

  @Test
  void an_unknown_scenario_is_a_404() throws Exception {
    mvc()
        .perform(post("/admin/scenarios/{name}", "no-such-thing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void reset_empties_the_erp() throws Exception {
    catalog.load("clean-match");

    mvc().perform(post("/admin/reset")).andExpect(status().isNoContent());

    assertThat(count("invoice")).isZero();
    assertThat(count("vendor")).isZero();
  }
}
