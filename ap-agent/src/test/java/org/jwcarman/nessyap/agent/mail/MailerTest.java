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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Message;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailerTest extends ApAgentIntegrationTest {

  @Autowired Mailer mailer;
  @Autowired JavaMailSenderImpl sender;

  @Test
  void what_is_recorded_is_the_message_id_that_was_sent() throws Exception {
    UUID exceptionId = openCase();

    MailSent sent =
        mailer.send(exceptionId, "vendor", "billing@acme.example", "About INV-1", "Hello");

    Message received = mailbox.awaitOne("billing@acme.example");
    assertThat(received.getHeader("Message-ID")).containsExactly(sent.messageId());
    assertThat(
            jdbc.sql("select message_id from outbound_mail where id = :id")
                .param("id", sent.id())
                .query(String.class)
                .single())
        .isEqualTo(sent.messageId());
  }

  @Test
  void every_subject_carries_the_case_token() throws Exception {
    UUID exceptionId = openCase();

    mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "Which PO?", "Hello");

    assertThat(mailbox.awaitOne("bob@nessy-ap.example").getSubject())
        .isEqualTo("[AP " + exceptionId + "] Which PO?");
  }

  @Test
  void a_send_that_fails_records_nothing() {
    UUID exceptionId = openCase();
    int port = sender.getPort();
    sender.setPort(1);
    try {
      assertThatThrownBy(() -> mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "s", "b"))
          .isInstanceOf(MailException.class);
    } finally {
      sender.setPort(port);
    }
    List<String> recorded =
        jdbc.sql("select message_id from outbound_mail where exception_id = :id")
            .param("id", exceptionId)
            .query(String.class)
            .list();
    assertThat(recorded).isEmpty();
  }
}
