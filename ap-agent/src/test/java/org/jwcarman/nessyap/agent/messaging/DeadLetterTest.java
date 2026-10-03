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
package org.jwcarman.nessyap.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** An event the agent can never handle is kept, not dropped, once its deliveries run out. */
class DeadLetterTest extends ApAgentIntegrationTest {

  @Autowired RabbitTemplate rabbit;
  @Autowired JsonMapper json;

  @Test
  void an_event_that_keeps_failing_ends_up_in_the_dead_letter_queue() {
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "INV-DEAD",
            UUID.randomUUID(),
            "PO-DEAD",
            ReasonCode.NO_RECEIPT,
            "s",
            BigDecimal.ONE);
    jdbc.sql(
            """
            create or replace function fail_inbound() returns trigger language plpgsql as
            $body$ begin raise exception 'simulated database trouble'; end $body$
            """)
        .update();
    jdbc.sql(
            "create trigger fail_inbound before insert on inbound_event for each row"
                + " execute function fail_inbound()")
        .update();
    Message dead;
    try {
      rabbit.send(
          ErpEvents.EXCHANGE,
          "match-exception.raised",
          MessageBuilder.withBody(json.writeValueAsBytes(raised))
              .setContentType(MessageProperties.CONTENT_TYPE_JSON)
              .setMessageId(raised.eventId().toString())
              .setType("match-exception.raised")
              .build());

      dead =
          await()
              .atMost(Duration.ofSeconds(60))
              .until(() -> rabbit.receive(ErpEvents.AGENT_DEAD_LETTER_QUEUE), Objects::nonNull);
    } finally {
      jdbc.sql("drop trigger fail_inbound on inbound_event").update();
    }

    assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(raised.eventId().toString());
  }
}
