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
package org.jwcarman.nessyap.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.ErpEvent;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.contracts.ReceiptPosted;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class ErpEventListenerTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin amqpAdmin;
  @Autowired JsonMapper json;
  @Autowired Cases cases;

  private static MatchExceptionRaised raised(UUID exceptionId, String poNumber) {
    return new MatchExceptionRaised(
        UUID.randomUUID(),
        Instant.now(),
        exceptionId,
        UUID.randomUUID(),
        "INV-" + exceptionId.toString().substring(0, 8),
        UUID.randomUUID(),
        poNumber,
        ReasonCode.QTY_OVER_RECEIPT,
        "Line 1 billed 100 but 60 received",
        new BigDecimal("400.00"));
  }

  private void publish(ErpEvent event) {
    String type = ErpEvents.routingKey(event);
    publish(type, event.eventId().toString(), json.writeValueAsBytes(event));
  }

  private void publish(String type, String messageId, byte[] body) {
    Message message =
        MessageBuilder.withBody(body)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setMessageId(messageId)
            .setType(type)
            .build();
    rabbit.send(ErpEvents.EXCHANGE, type, message);
  }

  private long turnsStarted(AgentId agent) {
    return narration.count(agent, Narration.TurnStarted.class);
  }

  private void awaitDrained() {
    await()
        .atMost(PATIENCE)
        .until(() -> amqpAdmin.getQueueInfo(ErpEvents.AGENT_QUEUE).getMessageCount() == 0);
  }

  @Test
  void a_raised_exception_opens_a_case_and_starts_its_agent() {
    UUID exceptionId = UUID.randomUUID();
    AgentId agent = cases.agentFor(exceptionId);

    publish(raised(exceptionId, "PO-A"));

    await().atMost(PATIENCE).until(() -> turnsStarted(agent) == 1);
    assertThat(cases.find(exceptionId)).isPresent();
  }

  @Test
  void the_same_event_delivered_twice_starts_one_turn() {
    UUID exceptionId = UUID.randomUUID();
    AgentId agent = cases.agentFor(exceptionId);
    MatchExceptionRaised event = raised(exceptionId, "PO-B");

    publish(event);
    publish(event);

    awaitDrained();
    await().during(Duration.ofSeconds(2)).atMost(PATIENCE).until(() -> turnsStarted(agent) == 1);
  }

  @Test
  void a_receipt_reaches_every_open_case_on_its_po() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    publish(raised(first, "PO-SHARED"));
    publish(raised(second, "PO-SHARED"));
    await()
        .atMost(PATIENCE)
        .until(
            () ->
                turnsStarted(cases.agentFor(first)) == 1
                    && turnsStarted(cases.agentFor(second)) == 1);

    publish(new ReceiptPosted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), "PO-SHARED"));

    await()
        .atMost(PATIENCE)
        .until(
            () ->
                turnsStarted(cases.agentFor(first)) == 2
                    && turnsStarted(cases.agentFor(second)) == 2);
  }

  @Test
  void a_receipt_for_a_po_with_no_case_is_simply_consumed() {
    UUID receiptEvent = UUID.randomUUID();

    publish(new ReceiptPosted(receiptEvent, Instant.now(), UUID.randomUUID(), "PO-NOBODY"));

    await()
        .atMost(PATIENCE)
        .until(
            () ->
                jdbc.sql("select count(*) from inbound_event where event_id = :id")
                        .param("id", receiptEvent)
                        .query(Long.class)
                        .single()
                    == 1);
  }

  @Test
  void a_malformed_message_is_dropped_and_the_listener_keeps_going() {
    publish("match-exception.raised", "garbage-1", "not json".getBytes(StandardCharsets.UTF_8));
    UUID exceptionId = UUID.randomUUID();

    publish(raised(exceptionId, "PO-C"));

    await().atMost(PATIENCE).until(() -> turnsStarted(cases.agentFor(exceptionId)) == 1);
    awaitDrained();
  }
}
