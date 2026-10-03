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
package org.jwcarman.nessyap.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DecisionApiTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);
  private static final UUID INVOICE = UUID.fromString("01a0ffe1-29f3-7457-88d0-bc59aa5c810b");
  private static final String INVOICE_JSON =
      "{\"invoice\":{\"id\":\""
          + INVOICE
          + "\",\"status\":\"EXCEPTION\",\"version\":1,"
          + "\"approvedAmount\":null},\"exceptions\":[]}";

  @Autowired WebApplicationContext web;
  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  private MockMvc mvc;
  private UUID exceptionId;
  private AgentId agentId;

  static JwtRequestPostProcessor bearer(String username, String role) {
    return jwt()
        .jwt(
            token ->
                token
                    .tokenValue("token-of-" + username)
                    .subject(username)
                    .claim("preferred_username", username)
                    .claim("realm_access", Map.of("roles", List.of(role))))
        .authorities(RealmRoles::authorities);
  }

  @BeforeEach
  void aHoldWaitingOnAClerk() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    erp.on("GET", "/api/invoices/" + INVOICE, 200, INVOICE_JSON);
    erp.on("POST", "/api/invoices/" + INVOICE + "/hold", 200, INVOICE_JSON);
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"hold\",\"rationale\":\"waiting on goods\",\"evidence\":[]}")));
    exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-1",
            UUID.randomUUID(),
            null,
            ReasonCode.NO_RECEIPT,
            "nothing received",
            new BigDecimal("1000.00"));
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
  void a_clerk_sees_the_hold_waiting_on_clerks() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(get("/api/decisions").with(bearer("clara", "ap-clerk")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id").value(hasItem(proposal.id().toString())));
  }

  @Test
  void a_buyer_does_not_see_it() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(get("/api/decisions").with(bearer("bob", "buyer")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + proposal.id() + "')]").isEmpty());
  }

  @Test
  void a_buyer_cannot_decide_it() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(
            post("/api/decisions/{id}", proposal.id())
                .with(bearer("bob", "buyer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void a_clerk_decides_it_and_the_erp_sees_the_clerks_token() throws Exception {
    PendingDecision proposal = awaitProposal();

    mvc.perform(
            post("/api/decisions/{id}", proposal.id())
                .with(bearer("clara", "ap-clerk"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true,\"comment\":\"agreed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("DECIDED"));

    assertThat(erp.seen())
        .filteredOn(seen -> seen.method().equals("POST"))
        .singleElement()
        .satisfies(
            post -> assertThat(post.header("Authorization")).isEqualTo("Bearer token-of-clara"));
  }

  @Test
  void the_trail_shows_the_auditor_who_decided_and_what_the_turns_cost() throws Exception {
    PendingDecision proposal = awaitProposal();
    mvc.perform(
        post("/api/decisions/{id}", proposal.id())
            .with(bearer("clara", "ap-clerk"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"approve\":true}"));
    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);

    mvc.perform(get("/api/cases/{id}/trail", exceptionId).with(bearer("audrey", "auditor")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decisions[0].decidedBy").value("clara"))
        .andExpect(jsonPath("$.decisions[0].requiredRole").value("ap-clerk"))
        .andExpect(jsonPath("$.decisions[0].replyToken").doesNotExist())
        .andExpect(jsonPath("$.turns[0].tokens").isNumber())
        .andExpect(jsonPath("$.timeline").isNotEmpty());
  }

  @Test
  void a_clerk_cannot_read_the_trail() throws Exception {
    awaitProposal();

    mvc.perform(get("/api/cases/{id}/trail", exceptionId).with(bearer("clara", "ap-clerk")))
        .andExpect(status().isForbidden());
  }
}
