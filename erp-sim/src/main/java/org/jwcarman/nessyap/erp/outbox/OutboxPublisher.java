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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves outbox rows to RabbitMQ. A row is marked published only after the broker confirms it, in
 * the same transaction that locked it, so a crash or a broker outage means the row is sent again
 * later: delivery is at least once, and consumers deduplicate on the event id.
 */
@Component
@ConditionalOnProperty(
    name = "erp.outbox.publishing.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final int BATCH = 50;
  private static final String LOCK_PENDING =
      """
      select id, event_type, payload::text as payload from outbox
      where published_at is null
      order by created_at, id
      limit :limit
      for update skip locked
      """;

  private final JdbcClient jdbc;
  private final RabbitTemplate rabbit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OutboxPublisher(
      JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, Clock clock) {
    this.jdbc = jdbc;
    this.rabbit = rabbit;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${erp.outbox.poll-interval-ms:500}")
  public void publishPending() {
    try {
      while (publishBatch() == BATCH) {
        // a full batch means there may be more waiting
      }
    } catch (AmqpException e) {
      log.warn(
          "Outbox publishing failed; the events stay queued and will be retried: {}",
          e.getMessage());
    }
  }

  private int publishBatch() {
    // The callback always returns a count, so the transaction's result is never null.
    return Objects.requireNonNull(
        tx.execute(
            status -> {
              List<Pending> pending =
                  jdbc.sql(LOCK_PENDING)
                      .param("limit", BATCH)
                      .query(
                          (rs, row) ->
                              new Pending(
                                  rs.getObject("id", UUID.class),
                                  rs.getString("event_type"),
                                  rs.getString("payload")))
                      .list();
              if (pending.isEmpty()) {
                return 0;
              }
              rabbit.invoke(
                  operations -> {
                    for (Pending event : pending) {
                      operations.send(ErpEvents.EXCHANGE, event.type(), message(event));
                    }
                    operations.waitForConfirmsOrDie(5_000);
                    return null;
                  });
              jdbc.sql("update outbox set published_at = :now where id in (:ids)")
                  .param("now", Timestamp.from(clock.instant()))
                  .param("ids", pending.stream().map(Pending::id).toList())
                  .update();
              return pending.size();
            }));
  }

  private static Message message(Pending event) {
    return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setMessageId(event.id().toString())
        .setType(event.type())
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
        .build();
  }

  private record Pending(UUID id, String type, String payload) {}
}
