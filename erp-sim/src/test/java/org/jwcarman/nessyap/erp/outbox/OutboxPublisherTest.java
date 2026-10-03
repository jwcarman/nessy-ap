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
package org.jwcarman.nessyap.erp.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpContainers;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.vendor.BankChangeProposal;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxPublisherTest extends ErpIntegrationTest {

  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin amqpAdmin;
  @Autowired VendorMaster vendors;

  @BeforeEach
  void emptyTheTap() {
    amqpAdmin.purgeQueue(ErpContainers.TAP_QUEUE, false);
  }

  @Test
  void delivers_an_outboxed_event_to_the_exchange_and_marks_it_published() {
    Vendor vendor = data().vendor();
    vendors.proposeBankChange(
        Actor.system(),
        vendor.id(),
        new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"));
    UUID eventId =
        jdbc.sql("select id from outbox where event_type = 'vendor.bank-change.proposed'")
            .query(UUID.class)
            .single();

    Message message =
        await().atMost(Duration.ofSeconds(10)).until(() -> nextWithId(eventId), Objects::nonNull);

    MessageProperties properties = message.getMessageProperties();
    assertThat(properties.getReceivedRoutingKey()).isEqualTo("vendor.bank-change.proposed");
    assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
        .contains(vendor.id().toString());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                jdbc.sql("select published_at is not null from outbox where id = :id")
                    .param("id", eventId)
                    .query(Boolean.class)
                    .single());
  }

  private Message nextWithId(UUID eventId) {
    Message message;
    while ((message = rabbit.receive(ErpContainers.TAP_QUEUE)) != null) {
      if (eventId.toString().equals(message.getMessageProperties().getMessageId())) {
        return message;
      }
    }
    return null;
  }
}
