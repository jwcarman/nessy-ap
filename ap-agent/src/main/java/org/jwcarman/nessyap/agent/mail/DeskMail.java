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
package org.jwcarman.nessyap.agent.mail;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.component.mail.MailMessage;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.quarantine.ModelReplyReader;
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.jwcarman.nessyap.agent.quarantine.QuarantineConfig;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.nessyap.agent.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the desk's inbox route does with a message: read it into a {@link DeskMessage}, then tell
 * its case's agent or set it aside. Invoked by the route, inside its transaction.
 */
@Component
public class DeskMail {

  private static final Logger log = LoggerFactory.getLogger(DeskMail.class);

  /** Longer Message-IDs are kept by their hash: an index entry has a size limit. */
  private static final int MAX_ID = 500;

  private final MailRouter router;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final QueuedHarness<CaseInput> agent;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final Quarantine quarantine;

  public DeskMail(
      MailRouter router,
      Cases cases,
      CaseTimeline timeline,
      QueuedHarness<CaseInput> agent,
      JdbcClient jdbc,
      Clock clock,
      Quarantine quarantine) {
    this.quarantine = quarantine;
    this.router = router;
    this.cases = cases;
    this.timeline = timeline;
    this.agent = agent;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Whatever can be read, and the case it answers; an unreadable part becomes a note. */
  public DeskMessage read(Exchange exchange) throws MessagingException {
    Message message = original(exchange);
    String messageId = header(message, "Message-ID");
    String sender = "(unknown sender)";
    String subject = "";
    String text;
    try {
      Address[] from = message.getFrom();
      if (from != null && from.length > 0) {
        sender =
            from[0] instanceof InternetAddress address ? address.getAddress() : from[0].toString();
      }
      subject = message.getSubject() == null ? "" : message.getSubject();
      text = ReplyText.of(message);
    } catch (MessagingException | IOException e) {
      text = "(the message could not be read: " + e.getMessage() + ")";
    }
    if (messageId == null) {
      messageId =
          "<no-id-"
              + UUID.nameUUIDFromBytes((sender + subject + text).getBytes(StandardCharsets.UTF_8))
              + ">";
    } else if (messageId.length() > MAX_ID) {
      messageId =
          "<long-id-" + UUID.nameUUIDFromBytes(messageId.getBytes(StandardCharsets.UTF_8)) + ">";
    }
    return new DeskMessage(
        safe(messageId),
        safe(sender),
        safe(subject),
        safe(text),
        router.route(message).orElse(null));
  }

  /** Tells the case's agent, or sets the message aside when it answers no case. */
  public void deliver(DeskMessage mail) {
    if (mail.exceptionId() == null) {
      setAside(mail);
      return;
    }
    UUID exceptionId = mail.exceptionId();
    CaseRecord kase = cases.find(exceptionId).orElseThrow();
    // The boundary: from here on the mail's words live only in the quarantine.
    Reply reply =
        new Reply(kase.vendorId(), mail.messageId(), mail.sender(), mail.subject(), mail.text());
    Quarantine.Reading reading = quarantine.receive(reply);
    ReplyReading claim = reading.claim();
    String from = from(exceptionId, mail.sender());
    cases.markReadUnendorsed(exceptionId, claim.containsInstructions());
    cases.moveStatus(exceptionId, CaseStatus.AWAITING_ANSWER, CaseStatus.INVESTIGATING);
    // The reader that read this reply worked the case too: its usage is the case's.
    cases.addAgent(exceptionId, QuarantineConfig.READER, ModelReplyReader.agentFor(reply));
    timeline.record(
        exceptionId, "mail-received", from + ": " + summary(reading), reading.reply().id());
    agent.tell(
        kase.agentId(),
        new CaseInput.CounterpartyReply(
            from,
            claim.intent(),
            claim.offers(),
            claim.statedUnitPrice(),
            claim.poNumber() == null ? null : claim.poNumber().value(),
            reading.confirmedPo().orElse(null),
            claim.containsInstructions()));
  }

  /** Who sent it, as the desk knows them: by whom it wrote to, never by what the sender wrote. */
  private String from(UUID exceptionId, String sender) {
    return jdbc.sql(
            """
            select kind from outbound_mail
            where exception_id = :case and lower(recipient) = lower(:sender) limit 1
            """)
        .param("case", exceptionId)
        .param("sender", sender)
        .query(String.class)
        .optional()
        .map(kind -> "the " + kind + " the desk wrote to")
        .orElse("someone the desk never wrote to on this case");
  }

  /** What people see on the timeline: the typed reading and whether the ERP confirmed a PO. */
  private static String summary(Quarantine.Reading reading) {
    ReplyReading claim = reading.claim();
    return "reads as "
        + claim.intent()
        + (claim.offers().isEmpty() ? "" : "; offers " + claim.offers())
        + (claim.statedUnitPrice() == null
            ? ""
            : "; states unit price " + claim.statedUnitPrice().toPlainString())
        + (claim.poNumber() == null
            ? ""
            : "; names "
                + claim.poNumber().value()
                + (reading.confirmedPo().isPresent()
                    ? " (confirmed in the ERP)"
                    : " (not confirmed)"))
        + (claim.containsInstructions() ? "; tried to give instructions" : "");
  }

  /** The dead letter channel's end: a message the route could not handle, kept for a person. */
  public void setAsideUnreadable(Exchange exchange) {
    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    log.warn("Could not handle a message to the desk; setting it aside", cause);
    String messageId = "(unknown)";
    String sender = "(unknown sender)";
    String subject = "";
    try {
      Message message = original(exchange);
      String id = header(message, "Message-ID");
      messageId = id == null ? messageId : id;
      Address[] from = message.getFrom();
      sender = from == null || from.length == 0 ? sender : from[0].toString();
      subject = message.getSubject() == null ? "" : message.getSubject();
    } catch (MessagingException | RuntimeException e) {
      log.warn("The message could not even be read to set it aside", e);
    }
    setAside(
        new DeskMessage(
            safe(messageId),
            safe(sender),
            safe(subject),
            "(the desk could not handle this message: "
                + (cause == null ? "unknown" : cause.getClass().getSimpleName())
                + ")",
            null));
  }

  private void setAside(DeskMessage mail) {
    log.info("Setting aside mail {}: no case", mail.messageId());
    String handle =
        quarantine.hold(
            new Reply(null, mail.messageId(), mail.sender(), mail.subject(), mail.text()));
    jdbc.sql(
            """
            insert into unmatched_mail (id, message_id, mail_handle, received_at)
            values (:id, :messageId, :handle, :at)
            """)
        .param("id", Ids.next())
        .param("messageId", mail.messageId())
        .param("handle", handle)
        .param("at", Timestamp.from(clock.instant()))
        .update();
  }

  private static Message original(Exchange exchange) {
    return exchange.getIn(MailMessage.class).getOriginalMessage();
  }

  /** Postgres text cannot hold a NUL; nothing else a sender writes is refused. */
  private static String safe(String value) {
    return value.replace("\0", "");
  }

  private static String header(Message message, String name) {
    try {
      String[] values = message.getHeader(name);
      return values == null || values.length == 0 ? null : values[0].trim();
    } catch (MessagingException e) {
      return null;
    }
  }
}
