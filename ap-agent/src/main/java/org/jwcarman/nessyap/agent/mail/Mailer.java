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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends mail from the desk about one case, and records what was sent. Every subject carries the
 * case token, and every message a Message-ID minted here, so a reply finds its case either way. The
 * row is written only after the server took the message: a failed send leaves no record.
 */
@Component
public class Mailer {

  private final JavaMailSender sender;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final String deskAddress;

  public Mailer(
      JavaMailSender sender,
      JdbcClient jdbc,
      Clock clock,
      @Value("${ap.mail.desk-address}") String deskAddress) {
    this.sender = sender;
    this.jdbc = jdbc;
    this.clock = clock;
    this.deskAddress = deskAddress;
  }

  /** The prefix every subject about the case starts with. */
  public static String caseToken(UUID exceptionId) {
    return "[AP " + exceptionId + "]";
  }

  public MailSent send(UUID exceptionId, String kind, String to, String subject, String body) {
    UUID id = Ids.next();
    String messageId = "<" + id + "@" + domainOf(deskAddress) + ">";
    String fullSubject = caseToken(exceptionId) + " " + subject;
    MimeMessage message = sender.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(deskAddress);
      helper.setTo(to);
      helper.setSubject(fullSubject);
      helper.setText(body);
      // JavaMailSenderImpl keeps a Message-ID set before sending (saveChanges would mint its own).
      message.setHeader("Message-ID", messageId);
    } catch (MessagingException e) {
      throw new MailPreparationException("Could not build the message to " + to, e);
    }
    sender.send(message);
    Instant now = clock.instant();
    jdbc.sql(
            """
            insert into outbound_mail
              (id, exception_id, kind, recipient, subject, body, message_id, sent_at)
            values (:id, :exceptionId, :kind, :to, :subject, :body, :messageId, :at)
            """)
        .param("id", id)
        .param("exceptionId", exceptionId)
        .param("kind", kind)
        .param("to", to)
        .param("subject", fullSubject)
        .param("body", body)
        .param("messageId", messageId)
        .param("at", Timestamp.from(now))
        .update();
    return new MailSent(id, messageId, fullSubject);
  }

  private static String domainOf(String address) {
    return address.substring(address.indexOf('@') + 1);
  }
}
