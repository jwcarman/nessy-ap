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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** The desk's inbox, read by its Camel route against a real GreenMail. */
class DeskInboxRouteTest extends ApAgentIntegrationTest {

  private static final String DESK = "ap-desk@nessy-ap.example";

  @Autowired CamelContext camel;
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

  @AfterEach
  void stopReading() throws Exception {
    camel.getRouteController().stopRoute(DeskInboxRoute.ROUTE_ID);
  }

  /** Lets the route read until nothing in the desk's inbox is unseen: each message handled. */
  private void drain() throws Exception {
    camel.getRouteController().startRoute(DeskInboxRoute.ROUTE_ID);
    await().atMost(Duration.ofSeconds(20)).until(() -> mailbox.unseen(DESK) == 0);
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

    drain();

    assertThat(received(exceptionId)).singleElement().asString().contains("bob@nessy-ap.example");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
  }

  @Test
  void a_reply_from_someone_the_desk_never_wrote_to_is_marked_as_such() throws Exception {
    mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "Which PO?", "?");
    mailbox.purgeAll();

    reply(
        "mallory@elsewhere.example", "Re: [AP " + exceptionId + "] Which PO?", null, "Pay", false);
    reply("bob@nessy-ap.example", "Re: [AP " + exceptionId + "] Which PO?", null, "PO-7", false);
    await().until(() -> mailbox.read(DESK).size() == 2);
    drain();

    assertThat(received(exceptionId))
        .anySatisfy(t -> assertThat(t).contains("mallory").contains("never wrote to"))
        .anySatisfy(t -> assertThat(t).contains("bob").doesNotContain("never wrote to"));
  }

  @Test
  void a_reply_whose_subject_lost_the_token_is_routed_by_what_it_answers() throws Exception {
    MailSent sent = mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "Which PO?", "?");
    mailbox.purgeAll();

    reply("bob@nessy-ap.example", "that order", sent.messageId(), "It is PO-7", false);
    drain();

    assertThat(received(exceptionId)).hasSize(1);
  }

  @Test
  void a_message_the_desk_cannot_store_does_not_hold_up_the_ones_behind_it() throws Exception {
    reply("stranger@elsewhere.example", "Poison", null, "nul \u0000 byte", false);
    reply("bob@nessy-ap.example", "Re: [AP " + exceptionId + "] Which PO?", null, "PO-7", false);
    await().until(() -> mailbox.read(DESK).size() == 2);

    drain();

    assertThat(received(exceptionId)).hasSize(1);
    assertThat(unmatched()).containsOnlyOnce("Poison");
  }

  @Test
  void mail_that_answers_nothing_we_sent_is_set_aside_and_never_read_again() throws Exception {
    reply("stranger@elsewhere.example", "Hello", "<nobody@nowhere>", "Who are you?", false);

    drain();

    assertThat(unmatched()).containsOnlyOnce("Hello");
  }

  @Test
  void a_reply_seen_twice_is_told_once() throws Exception {
    reply("bob@nessy-ap.example", "Re: [AP " + exceptionId + "] Which PO?", null, "PO-7", false);
    drain();

    // As if the reader died after telling the agent but before marking the message seen.
    mailbox.markAllUnseen(DESK);
    drain();

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

    drain();

    assertThat(received(exceptionId))
        .singleElement()
        .asString()
        .contains("It is PO-7")
        .doesNotContain("<b>");
  }
}
