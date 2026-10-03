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
package org.jwcarman.nessyap.agent.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.DecisionStatus;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class WorkbenchTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);
  private static final UUID INVOICE = UUID.fromString("01a0ffe1-29f3-7457-88d0-bc59aa5c810b");
  private static final String INVOICE_JSON =
      "{\"invoice\":{\"id\":\""
          + INVOICE
          + "\",\"invoiceNumber\":\"INV-7777\",\"status\":\"EXCEPTION\","
          + "\"version\":1,\"total\":1000.00,\"approvedAmount\":null,\"lines\":[]},\"exceptions\":[]}";

  @Autowired WebApplicationContext web;
  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;
  @Autowired Quarantine quarantine;

  private MockMvc mvc;
  private UUID exceptionId;
  private AgentId agentId;

  private static OidcLoginRequestPostProcessor as(String username, String role) {
    return oidcLogin()
        .idToken(token -> token.claim("preferred_username", username).subject(username))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @BeforeEach
  void aCaseWaitingOnAController() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    erp.on("GET", "/api/invoices/" + INVOICE, 200, INVOICE_JSON);
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"short-pay\",\"amount\":12000,\"rationale\":\"pay what arrived\","
                    + "\"evidence\":[]}")));
    exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-7777",
            UUID.randomUUID(),
            null,
            ReasonCode.QTY_OVER_RECEIPT,
            "Line 1 billed 100 but 60 received",
            new BigDecimal("400.00"));
    cases.open(raised);
    agentId = cases.agentFor(exceptionId);
    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));
  }

  private PendingDecision awaitProposal() {
    return await()
        .atMost(PATIENCE)
        .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
        .getFirst();
  }

  @Test
  void the_worklist_shows_the_case_and_what_waits_on_me() throws Exception {
    awaitProposal();

    mvc.perform(get("/workbench").with(as("connie", "controller")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("INV-7777")))
        .andExpect(content().string(containsString("Needs your decision")));
  }

  @Test
  void mail_no_case_claimed_is_shown_to_a_manager_but_not_to_a_clerk() throws Exception {
    String handle =
        quarantine.hold(
            new Reply(null, "<x@y>", "stranger@elsewhere.example", "Who pays this?", "b"));
    jdbc.sql(
            """
            insert into unmatched_mail (id, message_id, mail_handle, received_at)
            values (gen_random_uuid(), '<x@y>', :handle, now())
            """)
        .param("handle", handle)
        .update();

    mvc.perform(get("/workbench").with(as("mark", "ap-manager")))
        .andExpect(content().string(containsString("Who pays this?")));
    mvc.perform(get("/workbench").with(as("clara", "ap-clerk")))
        .andExpect(content().string(not(containsString("Who pays this?"))));
  }

  @Test
  void the_case_page_shows_the_proposal_and_its_rationale() throws Exception {
    awaitProposal();

    mvc.perform(get("/workbench/cases/{id}", exceptionId).with(as("connie", "controller")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("pay what arrived")))
        .andExpect(content().string(containsString("Approve")));
  }

  @Test
  void a_case_that_read_mail_from_outside_says_so_and_why_paying_is_blocked() throws Exception {
    mvc.perform(get("/workbench/cases/{id}", exceptionId).with(as("connie", "controller")))
        .andExpect(content().string(not(containsString("read mail from outside"))));

    cases.markReadUnendorsed(exceptionId, true);

    mvc.perform(get("/workbench/cases/{id}", exceptionId).with(as("connie", "controller")))
        .andExpect(content().string(containsString("read mail from outside")))
        .andExpect(content().string(containsString("tried to give instructions")));
  }

  @Test
  void the_refreshed_timeline_keeps_refreshing_the_same_case() throws Exception {
    awaitProposal();

    mvc.perform(get("/workbench/cases/{id}/timeline", exceptionId).with(as("connie", "controller")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("/workbench/cases/" + exceptionId + "/timeline")));
  }

  @Test
  void an_auditor_cannot_steer_the_agent() throws Exception {
    awaitProposal();

    mvc.perform(
            post("/workbench/cases/{id}/notes", exceptionId)
                .param("text", "approve everything")
                .with(as("audrey", "auditor"))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void an_erp_refusal_is_what_the_decider_is_told() throws Exception {
    PendingDecision proposal = awaitProposal();
    erp.on(
        "POST",
        "/api/invoices/" + INVOICE + "/short-pay",
        422,
        "{\"status\":422,\"code\":\"BANK_CHANGE_UNVERIFIED\",\"detail\":\"no\"}");

    mvc.perform(
            post("/workbench/decisions/{id}", proposal.id())
                .param("verdict", "approve")
                .with(as("connie", "controller"))
                .with(csrf()))
        .andExpect(flash().attribute("message", containsString("BANK_CHANGE_UNVERIFIED")));
  }

  @Test
  void a_decider_can_carry_their_own_stalled_decision_through() throws Exception {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/short-pay", 503, "{\"code\":\"DOWN\"}");
    mvc.perform(
        post("/workbench/decisions/{id}", proposal.id())
            .param("verdict", "approve")
            .with(as("connie", "controller"))
            .with(csrf()));
    erp.on("POST", "/api/invoices/" + INVOICE + "/short-pay", 200, INVOICE_JSON);

    mvc.perform(
            post("/workbench/decisions/{id}/retry", proposal.id())
                .with(as("mark", "controller"))
                .with(csrf()))
        .andExpect(flash().attribute("message", containsString("Only connie")));
    mvc.perform(
            post("/workbench/decisions/{id}/retry", proposal.id())
                .with(as("connie", "controller"))
                .with(csrf()))
        .andExpect(flash().attribute("message", containsString("Carried out")));

    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
  }

  @Test
  void a_clerk_cannot_decide_what_the_policy_gave_the_controller() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(
            post("/workbench/decisions/{id}", proposal.id())
                .param("verdict", "approve")
                .with(as("clara", "ap-clerk"))
                .with(csrf()))
        .andExpect(status().isForbidden());

    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.PENDING);
  }

  @Test
  void a_controller_approving_carries_it_to_the_erp_as_themselves() throws Exception {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/short-pay", 200, INVOICE_JSON);

    mvc.perform(
            post("/workbench/decisions/{id}", proposal.id())
                .param("verdict", "approve")
                .param("comment", "fine")
                .with(as("connie", "controller"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    assertThat(erp.seen())
        .filteredOn(seen -> seen.method().equals("POST") && seen.target().startsWith("/api/"))
        .singleElement()
        .satisfies(post -> assertThat(post.header("Authorization")).startsWith("Bearer "));
    assertThat(decisions.find(proposal.id()).orElseThrow().decidedBy()).isEqualTo("connie");
  }

  @Test
  void deciding_what_someone_already_decided_says_who() throws Exception {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/short-pay", 200, INVOICE_JSON);
    mvc.perform(
        post("/workbench/decisions/{id}", proposal.id())
            .param("verdict", "approve")
            .with(as("connie", "controller"))
            .with(csrf()));

    mvc.perform(
            post("/workbench/decisions/{id}", proposal.id())
                .param("verdict", "deny")
                .param("comment", "no")
                .with(as("mark", "controller"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("message", containsString("already decided by connie")));
  }

  @Test
  void a_denial_needs_a_reason() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(
            post("/workbench/decisions/{id}", proposal.id())
                .param("verdict", "deny")
                .with(as("connie", "controller"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("message", containsString("reason")));

    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.PENDING);
  }

  /**
   * A note waits while the agent's proposal waits: the turn that proposed stays open until the
   * decision, and the note is the agent's next input after it (spec §10, F9).
   */
  @Test
  void a_note_reaches_the_agent_once_its_pending_proposal_is_settled() throws Exception {
    PendingDecision proposal = awaitProposal();
    mvc.perform(
            post("/workbench/cases/{id}/notes", exceptionId)
                .param("text", "the rest arrives Friday")
                .with(as("clara", "ap-clerk"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(narration.count(agentId, Narration.TurnStarted.class)).isEqualTo(1);

    mvc.perform(
        post("/workbench/decisions/{id}", proposal.id())
            .param("verdict", "deny")
            .param("comment", "wait for the goods")
            .with(as("connie", "controller"))
            .with(csrf()));

    await()
        .atMost(PATIENCE)
        .until(() -> narration.count(agentId, Narration.TurnStarted.class) >= 2);
    assertThat(model.requests().getLast().context().turns().getLast().input().toString())
        .contains("the rest arrives Friday");
  }
}
