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
package org.jwcarman.nessyap.contracts;

/** Where ERP events are published and under which routing key. */
public final class ErpEvents {

  /** The durable topic exchange every ERP event is published to. */
  public static final String EXCHANGE = "erp.events";

  /**
   * Where an event goes when no queue is bound for its routing key: the alternate exchange of
   * {@link #EXCHANGE}. Without it RabbitMQ confirms the publish and drops the message.
   */
  public static final String UNROUTED_EXCHANGE = "erp.events.unrouted";

  /** The queue that collects unrouted events, so they can be seen and replayed. */
  public static final String UNROUTED_QUEUE = "erp.events.unrouted";

  /**
   * The AP agent's own quorum queue, bound to {@link #EXCHANGE} for raised exceptions and posted
   * receipts. Provisioned by the broker's definitions and declared again by the agent.
   */
  public static final String AGENT_QUEUE = "ap-agent.erp-events";

  /**
   * Where the agent queue dead-letters an event it could not handle. It feeds {@link
   * #AGENT_RETRY_QUEUE}, which holds the event for {@link #AGENT_RETRY_DELAY_MILLIS} and then hands
   * it back to {@link #AGENT_QUEUE}: a retry with a delay, counted by the broker in the message's
   * {@code x-death} header, so the count survives restarts.
   */
  public static final String AGENT_RETRY_EXCHANGE = "ap-agent.erp-events.retry";

  public static final String AGENT_RETRY_QUEUE = "ap-agent.erp-events.retry";

  public static final int AGENT_RETRY_DELAY_MILLIS = 2_000;

  /** Events the agent gave up on after {@link #AGENT_MAX_ATTEMPTS}, kept to be looked at. */
  public static final String AGENT_DEAD_LETTER_QUEUE = "ap-agent.erp-events.dead";

  public static final int AGENT_MAX_ATTEMPTS = 5;

  private ErpEvents() {}

  public static String routingKey(ErpEvent event) {
    return switch (event) {
      case MatchExceptionRaised _ -> "match-exception.raised";
      case ReceiptPosted _ -> "receipt.posted";
      case InvoiceResolved _ -> "invoice.resolved";
      case VendorBankChangeProposed _ -> "vendor.bank-change.proposed";
    };
  }
}
