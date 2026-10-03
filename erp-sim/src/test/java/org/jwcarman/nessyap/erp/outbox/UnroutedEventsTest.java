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
package org.jwcarman.nessyap.erp.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

class UnroutedEventsTest extends ErpIntegrationTest {

  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin amqpAdmin;

  @BeforeEach
  void emptyTheCatchAll() {
    amqpAdmin.purgeQueue(ErpEvents.UNROUTED_QUEUE, false);
  }

  @Test
  void an_event_no_queue_is_bound_for_lands_in_the_catch_all_instead_of_vanishing() {
    rabbit.send(
        ErpEvents.EXCHANGE,
        "nobody.listens",
        MessageBuilder.withBody("{}".getBytes()).setMessageId("m-1").build());

    Message caught =
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> rabbit.receive(ErpEvents.UNROUTED_QUEUE), Objects::nonNull);

    assertThat(caught.getMessageProperties().getMessageId()).isEqualTo("m-1");
    assertThat(caught.getMessageProperties().getReceivedRoutingKey()).isEqualTo("nobody.listens");
  }
}
