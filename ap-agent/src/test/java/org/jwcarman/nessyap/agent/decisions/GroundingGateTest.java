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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A proposal must cite only ids a tool returned to its agent. Measured: four times in about 1,650
 * runs a model copied an id wrongly and the proposal reached a person. The policy now refuses it,
 * naming the id, so the agent corrects it in the same turn.
 */
class GroundingGateTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  private final UUID vendor = UUID.randomUUID();
  private final UUID invoice = UUID.randomUUID();

  @BeforeEach
  void anErpWithOneInvoice() {
    erp.on(
        "GET",
        "/api/purchase-orders/PO-1",
        200,
        "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\",\"vendorId\":\"" + vendor + "\"}");
    erp.on(
        "GET",
        "/api/invoices/" + invoice,
        200,
        "{\"invoice\":{\"id\":\""
            + invoice
            + "\",\"total\":1040.00,\"status\":\"EXCEPTION\"},\"exceptions\":[]}");
    erp.on(
        "GET",
        "/api/vendors/" + vendor,
        200,
        "{\"id\":\"" + vendor + "\",\"bankAccounts\":[{\"status\":\"ACTIVE\"}]}");
  }

  private UUID openTheCase() {
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
    agent.tell(cases.agentFor(exceptionId), new CaseInput.ExceptionRaised(raised));
    return exceptionId;
  }

  private static String proposalCiting(String id) {
    return "{\"action\":\"approve-variance\",\"rationale\":\"4% on the buyer's PO.\","
        + "\"evidence\":[\""
        + id
        + "\"]}";
  }

  @Test
  void a_proposal_citing_an_id_no_tool_returned_is_refused_naming_it() {
    String mangled = invoice.toString().substring(0, 12);
    model.script(
        steps(
            call("c1", "get_invoice", "{}"),
            call("c2", "propose_resolution", proposalCiting(mangled))));

    UUID exceptionId = openTheCase();

    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(model.outcomesSeen())
                    .filteredOn(ToolOutcome.Denied.class::isInstance)
                    .singleElement()
                    .satisfies(
                        o -> assertThat(((ToolOutcome.Denied) o).reason()).contains(mangled)));
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }

  @Test
  void a_proposal_citing_what_it_read_this_turn_goes_to_its_decider() {
    model.script(
        steps(
            call("c1", "get_invoice", "{}"),
            call("c2", "propose_resolution", proposalCiting(invoice.toString()))));

    UUID exceptionId = openTheCase();

    await().atMost(PATIENCE).until(() -> !decisions.forCase(exceptionId).isEmpty());
    assertThat(model.outcomesSeen()).noneMatch(ToolOutcome.Denied.class::isInstance);
  }
}
