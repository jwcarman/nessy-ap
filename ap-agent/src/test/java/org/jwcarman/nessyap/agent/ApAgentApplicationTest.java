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

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class ApAgentApplicationTest extends ApAgentIntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired AmqpAdmin amqpAdmin;

  @Test
  void boots_with_the_queued_door_and_its_reply_desk() {
    assertThat(context.getBeansOfType(QueuedHarnessFactory.class)).isNotEmpty();
    assertThat(context.getBeansOfType(Replies.class)).isNotEmpty();
  }

  @Test
  void declares_its_own_quorum_queue() {
    Properties queue = amqpAdmin.getQueueProperties(ErpEvents.AGENT_QUEUE);

    assertThat(queue).isNotNull();
  }
}
