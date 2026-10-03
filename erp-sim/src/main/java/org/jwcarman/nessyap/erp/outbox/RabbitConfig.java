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

import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ERP's side of the topology: the exchange it publishes to, and a catch-all for events nobody
 * has bound a queue for. Consumers declare their own queues and bindings.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitConfig {

  @Bean
  public Exchange erpEvents() {
    return ExchangeBuilder.topicExchange(ErpEvents.EXCHANGE)
        .durable(true)
        .alternate(ErpEvents.UNROUTED_EXCHANGE)
        .build();
  }

  @Bean
  public FanoutExchange unroutedEvents() {
    return new FanoutExchange(ErpEvents.UNROUTED_EXCHANGE, true, false);
  }

  @Bean
  public Queue unroutedQueue() {
    return QueueBuilder.durable(ErpEvents.UNROUTED_QUEUE).quorum().build();
  }

  @Bean
  public Binding unroutedBinding(Queue unroutedQueue, FanoutExchange unroutedEvents) {
    return BindingBuilder.bind(unroutedQueue).to(unroutedEvents);
  }
}
