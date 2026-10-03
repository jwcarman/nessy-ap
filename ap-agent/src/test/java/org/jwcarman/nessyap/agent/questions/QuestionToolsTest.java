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
package org.jwcarman.nessyap.agent.questions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Message;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.tools.Calls;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

/** The agent asks the buyer on the workbench; a notice tells the buyer where, and nothing more. */
class QuestionToolsTest extends ApAgentIntegrationTest {

  @Autowired QuestionTools tools;
  @Autowired Questions questions;

  private ToolResult ask(AgentId agentId, String question, List<String> choices) {
    Awaited<ToolResult> awaited =
        tools.askBuyer().call(Calls.by(agentId, new QuestionTools.Ask(question, choices)));
    return ((Awaited.Ready<ToolResult>) awaited).value();
  }

  @Test
  void a_question_goes_to_the_buyer_the_erp_names_and_a_notice_says_where_to_answer()
      throws Exception {
    UUID exceptionId = openCase();
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");

    ToolResult result =
        ask(
            caseIndex.agentFor(exceptionId),
            "Did you agree the unit price of 11.60?",
            List.of("Agreed", "Not agreed"));

    assertThat(result).isInstanceOf(ToolResult.Success.class);
    assertThat(caseIndex.find(exceptionId).orElseThrow().status())
        .isEqualTo(CaseStatus.AWAITING_ANSWER);
    assertThat(questions.forCase(exceptionId))
        .singleElement()
        .satisfies(q -> assertThat(q.askedOf()).isEqualTo("bob"));
    Message notice = mailbox.awaitOne("bob@nessy-ap.example");
    assertThat(notice.getSubject()).doesNotContain("[AP ");
    assertThat(notice.getContent().toString()).contains("/workbench").doesNotContain("11.60");
  }

  @Test
  void a_second_question_waits_for_the_first_answer() {
    UUID exceptionId = openCase();
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");
    AgentId agentId = caseIndex.agentFor(exceptionId);
    ask(agentId, "One?", List.of());

    ToolResult second = ask(agentId, "Two?", List.of());

    assertThat(second)
        .isInstanceOfSatisfying(
            ToolResult.Failure.class, f -> assertThat(f.message()).contains("already waits"));
  }

  @Test
  void with_no_purchase_order_there_is_no_buyer_to_ask() {
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

    ToolResult result = ask(caseIndex.agentFor(noPo), "Which order?", List.of());

    assertThat(result)
        .isInstanceOfSatisfying(
            ToolResult.Failure.class, f -> assertThat(f.message()).contains("purchase order"));
    assertThat(questions.forCase(noPo)).isEmpty();
  }
}
