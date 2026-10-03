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

import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent's own queue and what it listens for. The broker's definitions provision the same
 * topology up front; declaring it here as well means a broker without them still works, and the
 * arguments must match the definitions exactly.
 */
@Configuration(proxyBeanMethods = false)
public class AgentQueues {

  @Bean
  public Declarables agentTopology() {
    Queue queue =
        QueueBuilder.durable(ErpEvents.AGENT_QUEUE)
            .quorum()
            .deadLetterExchange(ErpEvents.AGENT_RETRY_EXCHANGE)
            .build();
    // Declared with exactly erp-sim's arguments, so whichever app starts first creates it and the
    // other's declaration is a no-op. A mismatch would fail loudly with PRECONDITION_FAILED.
    TopicExchange erpEvents =
        ExchangeBuilder.topicExchange(ErpEvents.EXCHANGE)
            .durable(true)
            .alternate(ErpEvents.UNROUTED_EXCHANGE)
            .build();
    Binding raised = BindingBuilder.bind(queue).to(erpEvents).with("match-exception.raised");
    Binding receipts = BindingBuilder.bind(queue).to(erpEvents).with("receipt.posted");
    FanoutExchange retry = new FanoutExchange(ErpEvents.AGENT_RETRY_EXCHANGE, true, false);
    Queue retryQueue =
        QueueBuilder.durable(ErpEvents.AGENT_RETRY_QUEUE)
            .quorum()
            .ttl(ErpEvents.AGENT_RETRY_DELAY_MILLIS)
            .deadLetterExchange("")
            .deadLetterRoutingKey(ErpEvents.AGENT_QUEUE)
            .build();
    Queue dead = QueueBuilder.durable(ErpEvents.AGENT_DEAD_LETTER_QUEUE).quorum().build();
    return new Declarables(
        erpEvents,
        queue,
        raised,
        receipts,
        retry,
        retryQueue,
        BindingBuilder.bind(retryQueue).to(retry),
        dead);
  }
}
