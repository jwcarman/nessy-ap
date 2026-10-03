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
package org.jwcarman.nessyap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

class MailToolsTest extends ApAgentIntegrationTest {

  @Autowired MailTools tools;
  @Autowired CaseTimeline timeline;
  @Autowired QueuedHarness<CaseInput> agent;

  private UUID exceptionId;
  private CaseRecord kase;

  @BeforeEach
  void aCaseWhosePoBobPlaced() {
    exceptionId = openCase();
    kase = caseIndex.find(exceptionId).orElseThrow();
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");
    erp.on(
        "GET",
        "/api/vendors/" + kase.vendorId(),
        200,
        """
        {"contact": {"name": "Ann", "phone": "+1-555-0100", "email": "ann@acme.example"},
         "bankAccounts": [{"status": "ACTIVE"}]}
        """);
  }

  private ToolResult send(Tool<MailTools.Letter> tool, String subject, String body) {
    Awaited<ToolResult> awaited =
        tool.call(Calls.by(kase.agentId(), new MailTools.Letter(subject, body)));
    return ((Awaited.Ready<ToolResult>) awaited).value();
  }

  @Test
  void the_buyer_is_whoever_placed_the_order() throws Exception {
    ToolResult result = send(tools.emailBuyer(), "Which PO?", "Can you tell us?");

    assertThat(result).isInstanceOf(ToolResult.Success.class);
    assertThat(mailbox.awaitOne("bob@nessy-ap.example").getSubject())
        .isEqualTo("[AP " + exceptionId + "] Which PO?");
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::kind)
        .contains("tool", "mail-sent");
  }

  @Test
  void the_vendor_is_written_to_at_the_contact_of_record() {
    ToolResult result = send(tools.emailVendor(), "Credit memo", "Please credit the difference.");

    assertThat(result).isInstanceOf(ToolResult.Success.class);
    assertThat(mailbox.awaitOne("ann@acme.example")).isNotNull();
  }

  @Test
  void a_case_writes_to_the_buyer_at_most_three_times() throws Exception {
    for (int i = 1; i <= 3; i++) {
      assertThat(send(tools.emailBuyer(), "Chasing " + i, "Any news?"))
          .isInstanceOf(ToolResult.Success.class);
    }

    ToolResult fourth = send(tools.emailBuyer(), "Chasing 4", "Any news?");

    assertThat(fourth).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) fourth).message()).contains("3");
    await().until(() -> mailbox.read("bob@nessy-ap.example").size() == 3);
  }

  @Test
  void a_case_with_no_purchase_order_has_no_buyer_to_write_to() {
    UUID noPo = UUID.randomUUID();
    caseIndex.open(
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            noPo,
            UUID.randomUUID(),
            "INV-2",
            UUID.randomUUID(),
            null,
            ReasonCode.NO_PO,
            "s",
            new BigDecimal("100.00")));
    AgentId agentId = caseIndex.agentFor(noPo);

    Awaited<ToolResult> awaited =
        tools.emailBuyer().call(Calls.by(agentId, new MailTools.Letter("Which PO?", "?")));

    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) result).message()).contains("purchase order");
  }

  @Test
  void a_body_longer_than_four_thousand_characters_is_refused_not_cut() {
    ToolResult result = send(tools.emailBuyer(), "Long", "x".repeat(4001));

    assertThat(result).isInstanceOf(ToolResult.Failure.class);
  }

  @Test
  void the_agent_cannot_mail_a_vendor_whose_bank_details_changed_unverified() throws Exception {
    erp.on(
        "GET",
        "/api/vendors/" + kase.vendorId(),
        200,
        """
        {"contact": {"email": "ann@acme.example"},
         "bankAccounts": [{"status": "ACTIVE"}, {"status": "PENDING_VERIFICATION"}]}
        """);
    model.script(
        steps(call("c1", "email_vendor", "{\"subject\":\"Hello\",\"body\":\"Please confirm\"}")));

    agent.tell(kase.agentId(), new CaseInput.PersonNote("clara", "look at this"));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> narration.count(kase.agentId(), Narration.TurnEnded.class) == 1);
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("bank"));
    assertThat(mailbox.read("ann@acme.example")).isEmpty();
  }
}
