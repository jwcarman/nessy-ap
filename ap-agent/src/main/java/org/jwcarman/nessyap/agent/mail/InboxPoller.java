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

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the desk's inbox. Each unseen message is handled in one transaction: remember its
 * Message-ID (a second sighting stops there), then either tell the case's agent or set the message
 * aside as unmatched. Only after that commits is the message marked seen, so a crash in between
 * means it is read again and stopped by the remembered id, never told twice. A message that cannot
 * be read is set aside, not retried.
 */
@Component
public class InboxPoller {

  private static final Logger log = LoggerFactory.getLogger(InboxPoller.class);

  /** Longer Message-IDs are stored by their hash: an index entry has a size limit. */
  private static final int MAX_ID = 500;

  /** One message as the desk reads it. */
  private record Incoming(String messageId, String sender, String subject, String text) {}

  private final MailRouter router;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final QueuedHarness<CaseInput> agent;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String host;
  private final int port;
  private final String username;
  private final String password;

  public InboxPoller(
      MailRouter router,
      Cases cases,
      CaseTimeline timeline,
      QueuedHarness<CaseInput> agent,
      JdbcClient jdbc,
      TransactionTemplate tx,
      Clock clock,
      @Value("${ap.mail.imap.host}") String host,
      @Value("${ap.mail.imap.port}") int port,
      @Value("${ap.mail.imap.username}") String username,
      @Value("${ap.mail.imap.password}") String password) {
    this.router = router;
    this.cases = cases;
    this.timeline = timeline;
    this.agent = agent;
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
  }

  public void pollOnce() throws MessagingException {
    Store store = Session.getInstance(new Properties()).getStore("imap");
    store.connect(host, port, username, password);
    try (store) {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_WRITE);
      for (Message message : inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))) {
        handleOrSetAside(message);
        message.setFlag(Flags.Flag.SEEN, true);
      }
      inbox.close(false);
    }
  }

  /**
   * One message's failure never holds up the rest: a message that cannot be handled is set aside as
   * unreadable, and if even that fails it is only logged. Either way it is marked seen, so the
   * inbox never stalls on it.
   */
  private void handleOrSetAside(Message message) {
    try {
      tx.executeWithoutResult(status -> handle(message));
    } catch (RuntimeException e) {
      log.warn("Could not handle a message to the desk; setting it aside", e);
      try {
        tx.executeWithoutResult(status -> setAsideUnreadable(message, e));
      } catch (RuntimeException again) {
        log.error("Could not even set the message aside; it is marked seen and dropped", again);
      }
    }
  }

  private void setAsideUnreadable(Message message, RuntimeException cause) {
    Incoming read = read(message);
    Incoming unreadable =
        new Incoming(
            read.messageId(),
            read.sender(),
            read.subject(),
            "(the desk could not store this message: " + cause.getClass().getSimpleName() + ")");
    jdbc.sql(
            """
            insert into inbound_mail (message_id, received_at) values (:id, :at)
            on conflict (message_id) do nothing
            """)
        .param("id", unreadable.messageId())
        .param("at", Timestamp.from(clock.instant()))
        .update();
    setAside(unreadable);
  }

  private void handle(Message message) {
    Incoming incoming = read(message);
    int fresh =
        jdbc.sql(
                """
                insert into inbound_mail (message_id, received_at) values (:id, :at)
                on conflict (message_id) do nothing
                """)
            .param("id", incoming.messageId())
            .param("at", Timestamp.from(clock.instant()))
            .update();
    if (fresh == 0) {
      return;
    }
    Optional<UUID> exceptionId = route(message);
    if (exceptionId.isPresent()) {
      deliver(exceptionId.get(), incoming);
    } else {
      setAside(incoming);
    }
  }

  private void deliver(UUID exceptionId, Incoming incoming) {
    jdbc.sql("update inbound_mail set exception_id = :case where message_id = :id")
        .param("case", exceptionId)
        .param("id", incoming.messageId())
        .update();
    timeline.record(
        exceptionId,
        "mail-received",
        "from " + incoming.sender() + ": " + incoming.subject() + "\n" + incoming.text());
    agent.tell(
        cases.agentFor(exceptionId),
        new CaseInput.CounterpartyReply(incoming.sender(), incoming.text()));
  }

  private void setAside(Incoming incoming) {
    log.info("Setting aside mail {} from {}: no case", incoming.messageId(), incoming.sender());
    jdbc.sql(
            """
            insert into unmatched_mail (id, message_id, sender, subject, body, received_at)
            values (:id, :messageId, :sender, :subject, :body, :at)
            """)
        .param("id", Ids.next())
        .param("messageId", incoming.messageId())
        .param("sender", incoming.sender())
        .param("subject", incoming.subject())
        .param("body", incoming.text())
        .param("at", Timestamp.from(clock.instant()))
        .update();
  }

  private Optional<UUID> route(Message message) {
    try {
      return router.route(message);
    } catch (MessagingException e) {
      log.warn("Could not read the routing headers of a message; setting it aside", e);
      return Optional.empty();
    }
  }

  /** Whatever can be read; an unreadable part becomes a note, never an exception that loops. */
  private static Incoming read(Message message) {
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
    return new Incoming(safe(messageId), safe(sender), safe(subject), safe(text));
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
