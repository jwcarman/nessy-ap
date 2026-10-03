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
package org.jwcarman.nessyap.agent;

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
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** From an ERP event on the broker to a resolved case, with the auto-decider approving. */
@TestPropertySource(properties = "ap.decisions.auto=true")
class AgentEndToEndTest extends ApAgentIntegrationTest {

  private static final UUID INVOICE = UUID.fromString("01a0ffe1-29f3-7457-88d0-bc59aa5c810b");
  private static final String INVOICE_JSON =
      "{\"invoice\":{\"id\":\""
          + INVOICE
          + "\",\"status\":\"EXCEPTION\",\"version\":1,"
          + "\"approvedAmount\":null},\"exceptions\":[]}";

  @Autowired RabbitTemplate rabbit;
  @Autowired JsonMapper json;
  @Autowired Cases cases;
  @Autowired CaseTimeline timeline;

  @Test
  void a_price_variance_is_investigated_proposed_approved_and_carried_out() {
    erp.on("GET", "/api/invoices/" + INVOICE, 200, INVOICE_JSON);
    erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\"}");
    erp.on("GET", "/api/purchase-orders/PO-1/receipts", 200, "[]");
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, INVOICE_JSON);
    model.script(
        steps(
            call("c1", "get_invoice", "{\"invoiceId\":\"" + INVOICE + "\"}"),
            call("c2", "get_purchase_order", "{\"poNumber\":\"PO-1\"}"),
            call("c3", "get_receipts", "{\"poNumber\":\"PO-1\"}"),
            call(
                "c4",
                "propose_resolution",
                "{\"action\":\"approve-variance\",\"rationale\":\"4% over, buyer agreed\","
                    + "\"evidence\":[\""
                    + INVOICE
                    + "\"]}")));
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-1001",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.PRICE_VARIANCE,
            "Line 1 billed 10.40 against PO price 10.00",
            new BigDecimal("40.00"));

    rabbit.send(
        ErpEvents.EXCHANGE,
        "match-exception.raised",
        MessageBuilder.withBody(json.writeValueAsBytes(raised))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setMessageId(raised.eventId().toString())
            .setType("match-exception.raised")
            .build());

    AgentId agentId = cases.agentFor(exceptionId);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
    assertThat(erp.seen())
        .extracting(seen -> seen.method() + " " + seen.target())
        .containsSubsequence(
            "GET /api/invoices/" + INVOICE,
            "GET /api/purchase-orders/PO-1",
            "GET /api/purchase-orders/PO-1/receipts",
            "POST /api/invoices/" + INVOICE + "/approve-variance");
    assertThat(cases.find(exceptionId).orElseThrow().status()).isEqualTo(CaseStatus.RESOLVED);
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::kind)
        .containsExactly("tool", "tool", "tool", "proposal", "decision", "resolved");
    assertThat(model.requests().getFirst().systemPrompt().value()).contains("VENDOR_BANK_CHANGED");
  }
}
