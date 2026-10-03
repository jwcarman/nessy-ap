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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.TestPropertySource;

/** Its own context: the approval term here is one second. */
@TestPropertySource(properties = "ap.approval.timeout=PT1S")
class LateDecisionTest extends ApAgentIntegrationTest {

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;
  @Autowired DecisionExecutor executor;

  @Test
  void a_decision_applied_after_its_approval_expired_is_told_to_the_agent() {
    UUID invoice = DecisionFlowTest.INVOICE;
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"hold\",\"rationale\":\"waiting on the buyer\",\"evidence\":[]}")));
    erp.on("GET", "/api/invoices/" + invoice, 200, DecisionFlowTest.INVOICE_JSON);
    erp.on("POST", "/api/invoices/" + invoice + "/hold", 200, DecisionFlowTest.INVOICE_JSON);
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            invoice,
            "INV-1001",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.NO_RECEIPT,
            "nothing received",
            new BigDecimal("1000.00"));
    cases.open(raised);
    AgentId agentId = cases.agentFor(exceptionId);
    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));
    PendingDecision proposal =
        await()
            .atMost(DecisionFlowTest.PATIENCE)
            .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
            .getFirst();
    await()
        .atMost(DecisionFlowTest.PATIENCE)
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
    assertThat(model.outcomesSeen()).anyMatch(ToolOutcome.Failed.class::isInstance);

    executor.decide(proposal.id(), "connie", true, "late but fine");

    await()
        .atMost(DecisionFlowTest.PATIENCE)
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 2);
    assertThat(erp.seen())
        .anyMatch(seen -> seen.method().equals("POST") && seen.target().startsWith("/api/"));
    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
  }
}
