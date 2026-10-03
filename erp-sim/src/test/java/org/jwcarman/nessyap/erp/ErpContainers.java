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
package org.jwcarman.nessyap.erp;

import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The real Postgres and RabbitMQ every integration test runs against. One Spring context, so one
 * pair of containers, is shared by every test class that imports this unchanged.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ErpContainers {

  /** A queue that hears vendor events, so a test can see what was published. */
  public static final String TAP_QUEUE = "test.erp-events.tap";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18.6-alpine");
  }

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbit() {
    return new RabbitMQContainer("rabbitmq:4.3.6-management-alpine");
  }

  @Bean
  Queue tapQueue() {
    return new Queue(TAP_QUEUE, true);
  }

  @Bean
  Binding tapBinding(Queue tapQueue) {
    return BindingBuilder.bind(tapQueue).to(new TopicExchange(ErpEvents.EXCHANGE)).with("vendor.#");
  }
}
