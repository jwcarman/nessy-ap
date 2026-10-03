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

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.ErpEvent;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReceiptPosted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns ERP events into case inputs. Each message is one small transaction: remember its event id,
 * tell the agent, commit, then ack. Nessy's {@code tell} joins the transaction, so a redelivered
 * event finds its id already remembered and tells nobody twice.
 */
@Component
public class ErpEventListener {

  private static final Logger log = LoggerFactory.getLogger(ErpEventListener.class);

  private final QueuedHarness<CaseInput> agent;
  private final Cases cases;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;

  public ErpEventListener(
      QueuedHarness<CaseInput> agent,
      Cases cases,
      JdbcClient jdbc,
      TransactionTemplate tx,
      JsonMapper json,
      Clock clock) {
    this.agent = agent;
    this.cases = cases;
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  @RabbitListener(queues = ErpEvents.AGENT_QUEUE, ackMode = "MANUAL")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    ErpEvent event;
    try {
      event = parse(message);
    } catch (JacksonException | IllegalArgumentException e) {
      // A message that can never be read is not retried: requeueing it would loop forever.
      log.warn(
          "Dropping unreadable ERP event {} ({}): {}",
          message.getMessageProperties().getMessageId(),
          message.getMessageProperties().getType(),
          e.getMessage());
      channel.basicReject(tag, false);
      return;
    }
    try {
      tx.executeWithoutResult(status -> handle(event));
      channel.basicAck(tag, false);
    } catch (RuntimeException e) {
      log.warn("Could not handle ERP event {}; it will be redelivered", event.eventId(), e);
      channel.basicNack(tag, false, true);
    }
  }

  private void handle(ErpEvent event) {
    if (!firstTimeSeen(event)) {
      return;
    }
    switch (event) {
      case MatchExceptionRaised raised -> {
        cases.open(raised);
        agent.tell(cases.agentFor(raised.exceptionId()), new CaseInput.ExceptionRaised(raised));
      }
      case ReceiptPosted receipt ->
          cases
              .openCasesForPo(receipt.poNumber())
              .forEach(agentId -> agent.tell(agentId, new CaseInput.ReceiptArrived(receipt)));
      default -> log.debug("Ignoring ERP event {} of a kind no case needs", event.eventId());
    }
  }

  private boolean firstTimeSeen(ErpEvent event) {
    return jdbc.sql(
                """
                insert into inbound_event (event_id, event_type, received_at)
                values (:id, :type, :at)
                on conflict (event_id) do nothing
                """)
            .param("id", event.eventId())
            .param("type", ErpEvents.routingKey(event))
            .param("at", Timestamp.from(clock.instant()))
            .update()
        == 1;
  }

  private ErpEvent parse(Message message) {
    String type = message.getMessageProperties().getType();
    Class<? extends ErpEvent> kind =
        switch (type == null ? "" : type) {
          case "match-exception.raised" -> MatchExceptionRaised.class;
          case "receipt.posted" -> ReceiptPosted.class;
          default -> throw new IllegalArgumentException("no case input for event type " + type);
        };
    ErpEvent event = json.readValue(message.getBody(), kind);
    if (event.eventId() == null) {
      throw new IllegalArgumentException("event has no id");
    }
    return event;
  }
}
