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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

class InboxPollerTest extends ApAgentIntegrationTest {

  private static final String DESK = "ap-desk@nessy-ap.example";

  @Autowired InboxPoller poller;
  @Autowired Mailer mailer;
  @Autowired JavaMailSender smtp;
  @Autowired CaseTimeline timeline;

  private UUID exceptionId;
  private AgentId agentId;

  @BeforeEach
  void aCaseTheDeskWroteAbout() {
    exceptionId = openCase();
    agentId = caseIndex.agentFor(exceptionId);
  }

  private void reply(String from, String subject, String inReplyTo, String text, boolean html)
      throws Exception {
    MimeMessage message = smtp.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
    helper.setFrom(from);
    helper.setTo(DESK);
    helper.setSubject(subject);
    helper.setText(text, html);
    if (inReplyTo != null) {
      message.setHeader("In-Reply-To", inReplyTo);
      message.setHeader("References", inReplyTo);
    }
    smtp.send(message);
    await().until(() -> mailbox.read(DESK).size() >= 1);
  }

  private List<String> received(UUID exceptionId) {
    return timeline.of(exceptionId).stream()
        .filter(e -> e.kind().equals("mail-received"))
        .map(CaseTimeline.CaseEvent::text)
        .toList();
  }

  private List<String> unmatched() {
    return jdbc.sql(
            "select subject from unmatched_mail where sender like 'stranger@%' order by received_at")
        .query(String.class)
        .list();
  }

  @Test
  void a_reply_carrying_the_case_token_reaches_the_case() throws Exception {
    reply("bob@nessy-ap.example", "Re: [AP " + exceptionId + "] Which PO?", null, "PO-7", false);

    poller.pollOnce();

    assertThat(received(exceptionId)).singleElement().asString().contains("bob@nessy-ap.example");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
  }

  @Test
  void a_reply_whose_subject_lost_the_token_is_routed_by_what_it_answers() throws Exception {
    MailSent sent = mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "Which PO?", "?");
    mailbox.purgeAll();

    reply("bob@nessy-ap.example", "that order", sent.messageId(), "It is PO-7", false);
    poller.pollOnce();

    assertThat(received(exceptionId)).hasSize(1);
  }

  @Test
  void mail_that_answers_nothing_we_sent_is_set_aside_and_never_read_again() throws Exception {
    reply("stranger@elsewhere.example", "Hello", "<nobody@nowhere>", "Who are you?", false);

    poller.pollOnce();
    poller.pollOnce();

    assertThat(unmatched()).containsExactly("Hello");
  }

  @Test
  void a_reply_seen_twice_is_told_once() throws Exception {
    reply("bob@nessy-ap.example", "Re: [AP " + exceptionId + "] Which PO?", null, "PO-7", false);
    poller.pollOnce();

    // As if the poller died after telling the agent but before marking the message seen.
    mailbox.markAllUnseen(DESK);
    poller.pollOnce();

    assertThat(received(exceptionId)).hasSize(1);
  }

  @Test
  void quoted_history_is_dropped_from_what_the_agent_reads() {
    assertThat(ReplyText.clean("Yes, agreed.\n\nOn Friday the desk wrote:\n> Was $10.40 agreed?\n"))
        .isEqualTo("Yes, agreed.\n\nOn Friday the desk wrote:");
  }

  @Test
  void html_only_mail_is_read_as_text() throws Exception {
    reply(
        "bob@nessy-ap.example",
        "Re: [AP " + exceptionId + "] Which PO?",
        null,
        "<p>It is <b>PO-7</b></p>",
        true);

    poller.pollOnce();

    assertThat(received(exceptionId))
        .singleElement()
        .asString()
        .contains("It is PO-7")
        .doesNotContain("<b>");
  }
}
