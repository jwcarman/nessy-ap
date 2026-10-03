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
import java.time.Duration;
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

/**
 * An agent that asks a question waits for the answer before it proposes. Measured live: an agent
 * asked the buyer, then in the same turn proposed approval on an answer it had made up, and the
 * overcharge was paid. The policy now refuses a proposal made in the turn that asked.
 */
class AskThenWaitTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  @Test
  void a_proposal_in_the_turn_that_asked_the_buyer_is_refused_before_anyone_is_asked() {
    UUID vendor = UUID.randomUUID();
    UUID invoice = UUID.randomUUID();
    erp.on(
        "GET",
        "/api/purchase-orders/PO-1",
        200,
        "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\",\"vendorId\":\"" + vendor + "\"}");
    erp.on(
        "GET",
        "/api/invoices/" + invoice,
        200,
        "{\"invoice\":{\"total\":1040.00,\"status\":\"EXCEPTION\"},\"exceptions\":[]}");
    model.script(
        steps(
            call("c1", "ask_buyer", "{\"question\":\"Was 10.40 agreed?\",\"choices\":[]}"),
            call(
                "c2",
                "propose_resolution",
                "{\"action\":\"approve-variance\",\"rationale\":\"The buyer agreed.\","
                    + "\"evidence\":[]}")));
    UUID exceptionId = UUID.randomUUID();
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
    AgentId agentId = cases.agentFor(exceptionId);

    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));

    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(
            o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("wait for the answer"));
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }
}
