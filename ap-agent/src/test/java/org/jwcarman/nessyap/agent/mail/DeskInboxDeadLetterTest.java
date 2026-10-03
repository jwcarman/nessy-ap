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
import static org.awaitility.Awaitility.await;

import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

/**
 * When handling a message fails part-way, the route's dead letter channel sets it aside in a
 * transaction of its own and rolls the failed one back: no remembered id, no timeline line, no
 * tell, and the message is marked seen so it never holds up the inbox. The failure is woven into
 * the route with AdviceWith, so this class gets a context of its own.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeskInboxDeadLetterTest extends ApAgentIntegrationTest {

  private static final String DESK = "ap-desk@nessy-ap.example";
  private static final String MESSAGE_ID = "<dead-letter-test@nessy-ap.example>";

  @Autowired CamelContext camel;
  @Autowired JavaMailSender smtp;
  @Autowired CaseTimeline timeline;
  @Autowired UnmatchedMail unmatchedMail;

  @Test
  void a_message_that_fails_part_way_is_set_aside_and_leaves_nothing_half_done() throws Exception {
    UUID exceptionId = openCase();
    AdviceWith.adviceWith(
        camel,
        DeskInboxRoute.ROUTE_ID,
        route ->
            route
                .weaveById(DeskInboxRoute.DELIVER)
                .before()
                .to(
                    "sql:insert into case_event (exception_id, at, kind, text)"
                        + " values ('"
                        + exceptionId
                        + "', now(), 'half', 'written before the fault')")
                .throwException(new IllegalStateException("the database went away")));
    MimeMessage reply = smtp.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(reply, "UTF-8");
    helper.setFrom("bob@nessy-ap.example");
    helper.setTo(DESK);
    helper.setSubject("Re: [AP " + exceptionId + "] Which PO?");
    helper.setText("PO-7");
    // JavaMailSenderImpl keeps a Message-ID set before sending, so the test knows its key.
    reply.setHeader("Message-ID", MESSAGE_ID);
    smtp.send(reply);
    await().until(() -> mailbox.unseen(DESK) == 1);

    camel.getRouteController().startRoute(DeskInboxRoute.ROUTE_ID);
    try {
      await().atMost(Duration.ofSeconds(20)).until(() -> mailbox.unseen(DESK) == 0);
    } finally {
      camel.getRouteController().stopRoute(DeskInboxRoute.ROUTE_ID);
    }

    // The set-aside message is in the quarantine; a manager reads it there.
    signIn("mark", "ap-manager");
    try {
      assertThat(unmatchedMail.recent(100))
          .filteredOn(m -> m.subject().contains(exceptionId.toString()))
          .singleElement()
          .satisfies(m -> assertThat(m.body()).contains("IllegalStateException"));
    } finally {
      SecurityContextHolder.clearContext();
    }
    assertThat(timeline.of(exceptionId)).isEmpty();
    assertThat(
            jdbc.sql("select count(*) from camel_messageprocessed where messageid = :id")
                .param("id", MESSAGE_ID)
                .query(Long.class)
                .single())
        .isZero();
  }

  private static void signIn(String user, String role) {
    TestingAuthenticationToken auth =
        new TestingAuthenticationToken(
            user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    auth.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
