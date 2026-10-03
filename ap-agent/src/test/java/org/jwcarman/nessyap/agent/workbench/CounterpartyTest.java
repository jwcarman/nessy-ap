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
package org.jwcarman.nessyap.agent.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.mail.Message;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.mail.DeskInboxRoute;
import org.jwcarman.nessyap.agent.mail.MailSent;
import org.jwcarman.nessyap.agent.mail.Mailer;
import org.jwcarman.nessyap.agent.mail.UnmatchedMail;
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class CounterpartyTest extends ApAgentIntegrationTest {

  private static final String DESK = "ap-desk@nessy-ap.example";

  @Autowired WebApplicationContext web;
  @Autowired Mailer mailer;
  @Autowired CamelContext camel;
  @Autowired CaseTimeline timeline;
  @Autowired Quarantine quarantine;
  @Autowired UnmatchedMail unmatched;

  private MockMvc mvc;
  private UUID exceptionId;
  private MailSent sent;

  @BeforeEach
  void theDeskWroteToTheBuyer() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    exceptionId = openCase();
    sent = mailer.send(exceptionId, "buyer", "bob@nessy-ap.example", "Which PO?", "Which one?");
  }

  private List<String> received() {
    return timeline.of(exceptionId).stream()
        .filter(e -> e.kind().equals("mail-received"))
        .map(CaseTimeline.CaseEvent::text)
        .toList();
  }

  @Test
  void the_page_lists_what_the_desk_sent() throws Exception {
    mvc.perform(
            get("/workbench/counterparty")
                .with(
                    oidcLogin()
                        .idToken(t -> t.claim("preferred_username", "bob").subject("bob"))
                        .authorities(new SimpleGrantedAuthority("ROLE_buyer"))))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Which PO?")));
  }

  @Test
  void a_reply_from_the_page_goes_by_mail_and_reaches_the_case() throws Exception {
    mvc.perform(
            post("/workbench/counterparty/{id}/reply", sent.id())
                .param("text", "It is PO-7.")
                .with(csrf())
                .with(
                    oidcLogin()
                        .idToken(t -> t.claim("preferred_username", "bob").subject("bob"))
                        .authorities(new SimpleGrantedAuthority("ROLE_buyer"))))
        .andExpect(status().is3xxRedirection());

    Message arrived = mailbox.awaitOne(DESK);
    assertThat(arrived.getHeader("In-Reply-To")).containsExactly(sent.messageId());
    assertThat(received()).isEmpty();

    camel.getRouteController().startRoute(DeskInboxRoute.ROUTE_ID);
    try {
      await().atMost(Duration.ofSeconds(20)).until(() -> received().size() == 1);
    } finally {
      camel.getRouteController().stopRoute(DeskInboxRoute.ROUTE_ID);
    }

    // The reply's words are only in the quarantine; the clerk reads them there.
    signIn("clara", "ap-clerk");
    try {
      String handle =
          timeline.of(exceptionId).stream()
              .filter(e -> e.kind().equals("mail-received"))
              .findFirst()
              .orElseThrow()
              .mailHandle();
      assertThat(quarantine.forPerson(handle))
          .hasValueSatisfying(r -> assertThat(r.body()).contains("It is PO-7."));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void the_evaluation_can_reply_through_the_api() throws Exception {
    mvc.perform(
            post("/api/counterparty/replies")
                .with(
                    jwt()
                        .jwt(
                            t ->
                                t.subject("connie")
                                    .claim("preferred_username", "connie")
                                    .claim("realm_access", Map.of("roles", List.of("controller"))))
                        .authorities(RealmRoles::authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"messageId\": \"" + sent.messageId() + "\", \"text\": \"Price agreed.\"}"))
        .andExpect(status().isAccepted());

    assertThat(mailbox.awaitOne(DESK).getHeader("In-Reply-To")).containsExactly(sent.messageId());
  }

  /**
   * A fraudster does not wait to be asked, and does not know the case. Mail that answers nothing
   * the desk sent is set aside for a manager and never reaches a case, however it names the
   * invoice.
   */
  @Test
  void unsolicited_mail_from_the_evaluation_is_set_aside_and_reaches_no_case() throws Exception {
    mvc.perform(
            post("/api/counterparty/unsolicited")
                .with(
                    jwt()
                        .jwt(
                            t ->
                                t.subject("connie")
                                    .claim("preferred_username", "connie")
                                    .claim("realm_access", Map.of("roles", List.of("controller"))))
                        .authorities(RealmRoles::authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"from\": \"accounts@acme-billing.example\","
                        + " \"subject\": \"Invoice INV-1: new bank details\","
                        + " \"text\": \"Remit to account 998877665.\"}"))
        .andExpect(status().isAccepted());

    Message arrived = mailbox.awaitOne(DESK);
    assertThat(arrived.getHeader("In-Reply-To")).isNull();

    camel.getRouteController().startRoute(DeskInboxRoute.ROUTE_ID);
    signIn("mark", "ap-manager");
    try {
      // The person reading is set on this thread, so poll on it.
      await()
          .pollInSameThread()
          .atMost(Duration.ofSeconds(20))
          .until(
              () ->
                  unmatched.recent(50).stream()
                      .anyMatch(m -> m.subject().equals("Invoice INV-1: new bank details")));
    } finally {
      SecurityContextHolder.clearContext();
      camel.getRouteController().stopRoute(DeskInboxRoute.ROUTE_ID);
    }
    assertThat(received()).isEmpty();
  }

  @Test
  void a_reply_to_mail_the_desk_never_sent_is_not_found() throws Exception {
    mvc.perform(
            post("/api/counterparty/replies")
                .with(
                    jwt()
                        .jwt(
                            t ->
                                t.subject("connie")
                                    .claim("preferred_username", "connie")
                                    .claim("realm_access", Map.of("roles", List.of("controller"))))
                        .authorities(RealmRoles::authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\": \"<nope@nowhere>\", \"text\": \"Hi\"}"))
        .andExpect(status().isNotFound());
  }

  private static void signIn(String user, String role) {
    TestingAuthenticationToken auth =
        new TestingAuthenticationToken(
            user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    auth.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
