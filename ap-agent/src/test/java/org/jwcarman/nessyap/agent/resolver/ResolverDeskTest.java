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
import static org.awaitility.Awaitility.await;

import jakarta.mail.Message;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.decisions.DecisionExecutor;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.SubstitutionReason;
import org.jwcarman.nessyap.agent.web.CaseController;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;

/** The rules work a case first: what they settle alone, what they ask, and what they hand over. */
class ResolverDeskTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);
  private static final String VENDOR_EMAIL = "ann@acme.example";

  @Autowired ResolverDesk resolverDesk;
  @Autowired TransactionTemplate tx;
  @Autowired Decisions decisions;
  @Autowired DecisionExecutor executor;
  @Autowired CaseTimeline timeline;
  @Autowired CaseController caseController;

  private UUID invoiceId;
  private UUID vendorId;
  private UUID exceptionId;
  private String poNumber;

  @BeforeEach
  void anInvoiceAgainstAPurchaseOrder() {
    invoiceId = UUID.randomUUID();
    vendorId = UUID.randomUUID();
    exceptionId = UUID.randomUUID();
    poNumber = "PO-" + exceptionId.toString().substring(0, 8);
    erp.on(
        "GET",
        "/api/vendors/" + vendorId,
        200,
        "{\"contact\":{\"email\":\""
            + VENDOR_EMAIL
            + "\"},\"bankAccounts\":[{\"status\":\"ACTIVE\"}]}");
    erp.on(
        "GET",
        "/api/purchase-orders/" + poNumber,
        200,
        "{\"poNumber\":\"" + poNumber + "\",\"lines\":[{\"lineNo\":1,\"unitPrice\":10.00}]}");
    erp.on(
        "GET",
        "/api/purchase-orders/" + poNumber + "/receipts",
        200,
        "[{\"id\":\"" + UUID.randomUUID() + "\"}]");
  }

  /** The ERP shows one line of 100 billed at {@code price}, as item {@code item}. */
  private void invoiceBills(String item, String price) {
    BigDecimal total = new BigDecimal(price).multiply(new BigDecimal("100"));
    erp.on(
        "GET",
        "/api/invoices/" + invoiceId,
        200,
        "{\"invoice\":{\"id\":\"%s\",\"status\":\"EXCEPTION\",\"version\":1,\"total\":%s,\"freight\":0,\"lines\":[{\"lineNo\":1,\"poLineNo\":1,\"itemCode\":\"%s\",\"quantity\":100,\"unitPrice\":%s}]},\"exceptions\":[]}"
            .formatted(invoiceId, total.toPlainString(), item, price));
  }

  private void raise(ReasonCode reasonCode) {
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            invoiceId,
            "INV-" + exceptionId.toString().substring(0, 8),
            vendorId,
            poNumber,
            reasonCode,
            "Line 1 does not match",
            new BigDecimal("40.00"));
    // As the ERP listener does it: open the case, and the rules look first, in one transaction.
    tx.executeWithoutResult(
        status -> {
          caseIndex.open(raised);
          resolverDesk.opened(raised);
        });
  }

  private AgentId agent() {
    return caseIndex.agentFor(exceptionId);
  }

  private PendingDecision awaitProposal(int count) {
    return await()
        .atMost(PATIENCE)
        .until(() -> decisions.forCase(exceptionId), list -> list.size() == count)
        .getLast();
  }

  private ReplyReading substitution(SubstitutionReason reason, String shipped) {
    return new ReplyReading(
        vendorId, Intent.SUBSTITUTED_ITEM, List.of(), null, null, false, reason, shipped);
  }

  @Test
  void a_small_price_variance_is_proposed_by_the_rules_and_no_agent_turn_starts() {
    invoiceBills("M8-HEX-ZN-100", "10.40");

    raise(ReasonCode.PRICE_VARIANCE);

    PendingDecision proposal = awaitProposal(1);
    assertThat(proposal.action()).isEqualTo("approve-variance");
    assertThat(proposal.replyToken()).isEqualTo(ResolverDesk.TOKEN);
    assertThat(caseIndex.rulesHandle(exceptionId)).isTrue();
    assertThat(narration.count(agent(), Narration.TurnStarted.class)).isZero();
  }

  @Test
  void the_case_view_shows_a_rules_proposal_as_made_by_the_rules_and_grounded() {
    invoiceBills("M8-HEX-ZN-100", "10.40");
    raise(ReasonCode.PRICE_VARIANCE);
    awaitProposal(1);
    TestingAuthenticationToken connie =
        new TestingAuthenticationToken(
            "connie", "n/a", List.of(new SimpleGrantedAuthority("ROLE_controller")));

    CaseController.CaseView view = caseController.get(exceptionId, connie);

    assertThat(view.handledBy()).isEqualTo("rules");
    assertThat(view.decisions())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.proposedBy()).isEqualTo("rules");
              assertThat(d.evidence()).contains(invoiceId.toString(), poNumber);
              assertThat(d.ungrounded()).isEmpty();
            });
  }

  @Test
  void an_approved_rules_proposal_is_carried_out_and_resolves_the_case() {
    invoiceBills("M8-HEX-ZN-100", "10.40");
    erp.on("POST", "/api/invoices/" + invoiceId + "/approve-variance", 200, "{}");
    raise(ReasonCode.PRICE_VARIANCE);
    PendingDecision proposal = awaitProposal(1);

    executor.decide(proposal.id(), "bob", true, "fine");

    await()
        .atMost(PATIENCE)
        .until(() -> caseIndex.find(exceptionId).orElseThrow().status() == CaseStatus.RESOLVED);
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::kind)
        .containsSubsequence("rules", "decision", "resolved");
    assertThat(narration.count(agent(), Narration.TurnStarted.class)).isZero();
  }

  @Test
  void a_case_no_rule_covers_goes_to_its_agent_with_what_the_rules_established() {
    invoiceBills("M8-HEX-ZN-100", "10.40");

    raise(ReasonCode.NO_PO);

    // The turn starts before its first model request is recorded: wait for the request itself.
    await().atMost(PATIENCE).until(() -> !model.requests().isEmpty());
    assertThat(caseIndex.rulesHandle(exceptionId)).isFalse();
    assertThat(model.requests().getFirst().context().turns().getLast().input().toString())
        .contains("rules worked this case first and stopped")
        .contains("reasonCode=NO_PO")
        .contains("The ERP raised match exception " + exceptionId);
  }

  @Test
  void a_substituted_item_is_asked_of_the_vendor_before_anything_is_proposed() throws Exception {
    invoiceBills("M8-HEX-SS-100", "11.20");

    raise(ReasonCode.ITEM_SUBSTITUTED);

    Message asked = mailbox.awaitOne(VENDOR_EMAIL);
    assertThat(asked.getSubject()).contains("why a different item");
    // The letter is on the case's record like any other the desk sends.
    assertThat(timeline.of(exceptionId))
        .filteredOn(line -> line.kind().equals("mail-sent"))
        .singleElement()
        .satisfies(line -> assertThat(line.text()).startsWith("vendor " + VENDOR_EMAIL));
    assertThat(caseIndex.find(exceptionId).orElseThrow().status())
        .isEqualTo(CaseStatus.AWAITING_ANSWER);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }

  @Test
  void a_vendor_reply_that_names_the_billed_item_lets_the_rules_propose_the_substitute() {
    invoiceBills("M8-HEX-SS-100", "11.20");
    raise(ReasonCode.ITEM_SUBSTITUTED);

    boolean taken =
        resolverDesk.replied(
            exceptionId, substitution(SubstitutionReason.OUT_OF_STOCK, "m8-hex-ss-100"));

    assertThat(taken).isTrue();
    assertThat(awaitProposal(1).action()).isEqualTo("approve-variance");
    assertThat(narration.count(agent(), Narration.TurnStarted.class)).isZero();
  }

  @Test
  void a_vendor_reply_about_another_item_hands_the_case_to_the_agent() {
    invoiceBills("M8-HEX-SS-100", "11.20");
    raise(ReasonCode.ITEM_SUBSTITUTED);

    resolverDesk.replied(
        exceptionId, substitution(SubstitutionReason.OUT_OF_STOCK, "M10-HEX-SS-100"));

    await()
        .atMost(PATIENCE)
        .until(() -> narration.count(agent(), Narration.TurnStarted.class) == 1);
    assertThat(caseIndex.rulesHandle(exceptionId)).isFalse();
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }

  @Test
  void a_declined_substitute_kept_at_the_po_price_becomes_a_short_pay_at_that_price() {
    invoiceBills("M8-HEX-SS-100", "11.20");
    raise(ReasonCode.ITEM_SUBSTITUTED);
    resolverDesk.replied(
        exceptionId, substitution(SubstitutionReason.OUT_OF_STOCK, "M8-HEX-SS-100"));
    PendingDecision offered = awaitProposal(1);
    caseIndex.rememberSlot(exceptionId, "declineReason", "PAY_PO_PRICE", "bob");

    executor.decide(offered.id(), "bob", false, "we pay what we ordered at");

    PendingDecision shortPay = awaitProposal(2);
    assertThat(shortPay.action()).isEqualTo("short-pay");
    assertThat(shortPay.amount()).isEqualByComparingTo("1000.00");
    assertThat(narration.count(agent(), Narration.TurnStarted.class)).isZero();
  }
}
