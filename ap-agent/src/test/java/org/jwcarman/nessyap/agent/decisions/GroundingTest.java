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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

/** A citation counts only when the agent read it: from the ERP, or in what it was told. */
class GroundingTest extends ApAgentIntegrationTest {

  private static final UUID INVOICE = UUID.randomUUID();
  private static final UUID ORIGINAL = UUID.randomUUID();
  private static final UUID NEVER_READ = UUID.randomUUID();

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Decisions decisions;
  @Autowired Grounding grounding;

  @Test
  void a_cited_id_the_agent_never_read_is_ungrounded() {
    erp.on(
        "GET",
        "/api/invoices/" + ORIGINAL,
        200,
        "{\"invoice\":{\"id\":\""
            + ORIGINAL
            + "\",\"poNumber\":\"PO-12\",\"status\":\"MATCHED\"},\"exceptions\":[]}");
    model.script(
        steps(
            call("c1", "get_invoice", "{\"invoiceId\":\"" + ORIGINAL + "\"}"),
            call(
                "c2",
                "propose_resolution",
                "{\"action\":\"reject\",\"rationale\":\"a repeat\",\"evidence\":[\""
                    + INVOICE
                    + "\",\""
                    + ORIGINAL
                    + "\",\""
                    + NEVER_READ
                    + "\",\"PO-1\"]}")));
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-1",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.DUPLICATE,
            "s",
            new BigDecimal("100.00"));
    caseIndex.open(raised);
    AgentId agentId = caseIndex.agentFor(exceptionId);

    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));

    // Only what a tool returned counts: not the opening message, not the agent's own words, and
    // never a fragment of a longer id. The policy refuses the proposal and names each one.
    String reason =
        await()
            .atMost(Duration.ofSeconds(20))
            .until(
                () ->
                    model.outcomesSeen().stream()
                        .filter(ToolOutcome.Denied.class::isInstance)
                        .map(o -> ((ToolOutcome.Denied) o).reason())
                        .findFirst(),
                Optional::isPresent)
            .orElseThrow();
    assertThat(reason)
        .contains(INVOICE.toString() + ", " + NEVER_READ + ", PO-1")
        .doesNotContain(ORIGINAL.toString());
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    assertThat(grounding.ungrounded(agentId, List.of(ORIGINAL.toString()))).isEmpty();
  }
}
