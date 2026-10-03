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

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The people the desk writes to, played by hand (dev only): what the desk sent, and a way to answer
 * it as its recipient. An answer is real mail to the desk, read by the inbox poller like any other.
 */
@Component
public class Counterparty {

  /** A message the desk sent. */
  public record Sent(
      UUID id,
      UUID exceptionId,
      String kind,
      String recipient,
      String subject,
      String body,
      String messageId,
      Instant sentAt) {}

  private final JdbcClient jdbc;
  private final JavaMailSender smtp;
  private final String deskAddress;

  public Counterparty(
      JdbcClient jdbc, JavaMailSender smtp, @Value("${ap.mail.desk-address}") String deskAddress) {
    this.jdbc = jdbc;
    this.smtp = smtp;
    this.deskAddress = deskAddress;
  }

  public List<Sent> recent(int limit) {
    return jdbc.sql("select * from outbound_mail order by sent_at desc limit :limit")
        .param("limit", limit)
        .query(Counterparty::sent)
        .list();
  }

  /** What the desk sent about one case, oldest first. */
  public List<Sent> forCase(UUID exceptionId) {
    return jdbc.sql("select * from outbound_mail where exception_id = :id order by sent_at")
        .param("id", exceptionId)
        .query(Counterparty::sent)
        .list();
  }

  public Optional<Sent> find(UUID id) {
    return jdbc.sql("select * from outbound_mail where id = :id")
        .param("id", id)
        .query(Counterparty::sent)
        .optional();
  }

  public Optional<Sent> findByMessageId(String messageId) {
    return jdbc.sql("select * from outbound_mail where message_id = :messageId")
        .param("messageId", messageId)
        .query(Counterparty::sent)
        .optional();
  }

  /** Answers a message as the one it was sent to. */
  public void reply(Sent original, String text) {
    MimeMessage message = smtp.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(original.recipient());
      helper.setTo(deskAddress);
      helper.setSubject("Re: " + original.subject());
      helper.setText(text);
      message.setHeader("In-Reply-To", original.messageId());
      message.setHeader("References", original.messageId());
    } catch (MessagingException e) {
      throw new MailPreparationException("Could not build the reply", e);
    }
    smtp.send(message);
  }

  /**
   * Writes to the desk out of the blue, as someone it never wrote to: it answers no message, so it
   * carries no In-Reply-To.
   */
  public void writeUnprompted(String from, String subject, String text) {
    MimeMessage message = smtp.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(from);
      helper.setTo(deskAddress);
      helper.setSubject(subject);
      helper.setText(text);
    } catch (MessagingException e) {
      throw new MailPreparationException("Could not build the message", e);
    }
    smtp.send(message);
  }

  private static Sent sent(ResultSet rs, int row) throws SQLException {
    return new Sent(
        rs.getObject("id", UUID.class),
        rs.getObject("exception_id", UUID.class),
        rs.getString("kind"),
        rs.getString("recipient"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getString("message_id"),
        rs.getTimestamp("sent_at").toInstant());
  }
}
