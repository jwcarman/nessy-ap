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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

class PolicyRoutingTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  private UUID exceptionId;
  private AgentId agentId;

  private void propose(ReasonCode code, String amountAtIssue, String proposal) {
    propose(code, amountAtIssue, "5000.00", proposal);
  }

  private void propose(
      ReasonCode code, String amountAtIssue, String invoiceTotal, String proposal) {
    propose(code, amountAtIssue, invoiceTotal, "EXCEPTION", proposal);
  }

  private void propose(
      ReasonCode code,
      String amountAtIssue,
      String invoiceTotal,
      String invoiceStatus,
      String proposal) {
    UUID vendor = UUID.randomUUID();
    UUID invoice = UUID.randomUUID();
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");
    erp.on(
        "GET",
        "/api/invoices/" + invoice,
        200,
        "{\"invoice\":{\"total\":"
            + invoiceTotal
            + ",\"status\":\""
            + invoiceStatus
            + "\"},\"exceptions\":[]}");
    model.script(steps(call("c1", "propose_resolution", proposal)));
    exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            invoice,
            "INV-1",
            vendor,
            "PO-1",
            code,
            "s",
            new BigDecimal(amountAtIssue));
    cases.open(raised);
    agentId = cases.agentFor(exceptionId);
    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));
  }

  @Test
  void a_hold_on_an_invoice_already_on_hold_is_refused_before_anyone_is_asked() {
    propose(
        ReasonCode.QTY_OVER_RECEIPT,
        "400.00",
        "1000.00",
        "ON_HOLD",
        "{\"action\":\"hold\",\"rationale\":\"r\",\"evidence\":[]}");

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("already on hold"));
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }

  static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of(ReasonCode.PRICE_VARIANCE, "40.00", "hold", null, "ap-clerk", null),
        Arguments.of(ReasonCode.PRICE_VARIANCE, "40.00", "approve-variance", null, "buyer", "bob"),
        Arguments.of(
            ReasonCode.UNPLANNED_CHARGE, "1600.00", "approve-variance", null, "ap-manager", null),
        Arguments.of(
            ReasonCode.QTY_OVER_RECEIPT, "400.00", "short-pay", "12000", "controller", null),
        Arguments.of(ReasonCode.DUPLICATE, "1000.00", "reject", null, "ap-manager", null));
  }

  @ParameterizedTest(name = "{2} on {0} goes to {4}")
  @MethodSource("routes")
  void each_proposal_waits_on_the_role_the_policy_names(
      ReasonCode code, String atIssue, String action, String amount, String role, String user) {
    propose(
        code,
        atIssue,
        "{\"action\":\""
            + action
            + "\","
            + (amount == null ? "" : "\"amount\":" + amount + ",")
            + "\"rationale\":\"r\",\"evidence\":[]}");

    PendingDecision pending =
        await()
            .atMost(PATIENCE)
            .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
            .getFirst();

    assertThat(pending.requiredRole()).isEqualTo(role);
    assertThat(pending.requiredUser()).isEqualTo(user);
  }

  @Test
  void a_small_variance_on_an_invoice_over_the_limit_goes_where_the_erp_will_accept_it() {
    propose(
        ReasonCode.PRICE_VARIANCE,
        "40.00",
        "12000.00",
        "{\"action\":\"approve-variance\",\"rationale\":\"r\",\"evidence\":[]}");

    PendingDecision pending =
        await()
            .atMost(PATIENCE)
            .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
            .getFirst();

    assertThat(pending.requiredRole()).isEqualTo("controller");
  }

  @Test
  void a_repeated_invoice_is_not_paid_through_its_price_variance() {
    UUID vendor = UUID.randomUUID();
    UUID invoice = UUID.randomUUID();
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");
    erp.on(
        "GET",
        "/api/invoices/" + invoice,
        200,
        """
        {"invoice": {"total": 1040.00},
         "exceptions": [{"reasonCode": "DUPLICATE", "status": "OPEN"},
                        {"reasonCode": "PRICE_VARIANCE", "status": "OPEN"}]}
        """);
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"approve-variance\",\"rationale\":\"r\"}")));
    exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            invoice,
            "INV-1",
            vendor,
            "PO-1",
            ReasonCode.PRICE_VARIANCE,
            "s",
            new BigDecimal("40.00"));
    cases.open(raised);
    agentId = cases.agentFor(exceptionId);
    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("same number"));
  }

  @Test
  void an_action_that_is_not_a_resolution_is_denied_without_asking_anyone() {
    propose(ReasonCode.PRICE_VARIANCE, "40.00", "{\"action\":\"pay-twice\",\"rationale\":\"r\"}");

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    assertThat(model.outcomesSeen()).anyMatch(ToolOutcome.Denied.class::isInstance);
  }

  @Test
  void paying_a_vendor_with_an_unverified_bank_change_is_denied_by_policy() {
    erp.onPrefix(
        "GET",
        "/api/vendors/",
        200,
        "{\"bankAccounts\":[{\"status\":\"ACTIVE\"},{\"status\":\"PENDING_VERIFICATION\"}]}");
    propose(
        ReasonCode.VENDOR_BANK_CHANGED,
        "1000.00",
        "{\"action\":\"approve-variance\",\"rationale\":\"r\"}");

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("bank"));
  }

  @Test
  void a_vendor_that_cannot_be_read_is_refused_until_it_can_be() {
    erp.onPrefix("GET", "/api/vendors/", 503, "{\"code\":\"DOWN\"}");
    propose(
        ReasonCode.PRICE_VARIANCE,
        "40.00",
        "{\"action\":\"approve-variance\",\"rationale\":\"r\"}");

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    // Refused, but not as fraud: a read that failed says nothing about the bank details.
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("could not read"));
  }
}
