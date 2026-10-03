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
package org.jwcarman.nessyap.agent.mail;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.spi.IdempotentRepository;
import org.springframework.stereotype.Component;

/**
 * The desk's inbox. Each unseen message is read, deduplicated by its Message-ID, and either told to
 * its case's agent or set aside, in one transaction: the idempotent key, the timeline and the tell
 * commit or roll back together, and the mail consumer marks the message seen only when the exchange
 * completes. A message that cannot be handled is set aside in a transaction of its own and the
 * failed one rolled back, so it is marked seen and never holds up the inbox.
 */
@Component
public class DeskInboxRoute extends RouteBuilder {

  public static final String ROUTE_ID = "desk-inbox";
  static final String SET_ASIDE = "direct:desk-set-aside";
  static final String DELIVER = "desk-deliver";

  private final IdempotentRepository handled;

  public DeskInboxRoute(IdempotentRepository deskInboxHandled) {
    this.handled = deskInboxHandled;
  }

  @Override
  public void configure() {
    onException(Exception.class)
        .handled(true)
        .useOriginalMessage()
        .to(SET_ASIDE)
        .markRollbackOnlyLast();

    from("imap://{{ap.mail.imap.host}}:{{ap.mail.imap.port}}"
            + "?username=RAW({{ap.mail.imap.username}})&password=RAW({{ap.mail.imap.password}})"
            + "&unseen=true&peek=true&delete=false&mapMailMessage=false"
            + "&delay={{ap.mail.poll.delay-ms}}&connectionTimeout=10000"
            + "&additionalJavaMailProperties=#deskImapTimeouts")
        .routeId(ROUTE_ID)
        .autoStartup("{{ap.mail.poll.enabled}}")
        .transacted()
        .bean(DeskMail.class, "read")
        .id("desk-read")
        .idempotentConsumer(simple("${body.messageId}"), handled)
        .skipDuplicate(true)
        .bean(DeskMail.class, "deliver")
        .id(DELIVER);

    from(SET_ASIDE)
        .routeId("desk-set-aside")
        .transacted(DeskMailConfig.REQUIRES_NEW)
        .bean(DeskMail.class, "setAsideUnreadable");
  }
}
