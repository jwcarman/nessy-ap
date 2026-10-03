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

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.vendor.BankChangeProposal;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** No scheduled publisher here, so nothing races the publisher this test drives by hand. */
@TestPropertySource(properties = "erp.outbox.publishing.enabled=false")
class OutboxPublisherBrokerDownTest extends ErpIntegrationTest {

  @Autowired TransactionTemplate tx;
  @Autowired Clock clock;
  @Autowired VendorMaster vendors;
  @Autowired ApplicationContext context;

  @Test
  void publishing_can_be_switched_off() {
    assertThat(context.getBeansOfType(OutboxPublisher.class)).isEmpty();
  }

  @Test
  void leaves_events_waiting_when_the_broker_is_unreachable() {
    Vendor vendor = data().vendor();
    vendors.proposeBankChange(
        Actor.system(),
        vendor.id(),
        new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"));
    CachingConnectionFactory nowhere = new CachingConnectionFactory("localhost", 1);
    try {
      new OutboxPublisher(jdbc, new RabbitTemplate(nowhere), tx, clock).publishPending();
    } finally {
      nowhere.destroy();
    }

    assertThat(
            jdbc.sql("select count(*) from outbox where published_at is null")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
