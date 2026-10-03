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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A citation counts only when the agent read it: from the ERP, or in what it was told. */
class GroundingTest extends ApAgentIntegrationTest {

  private static final UUID INVOICE = UUID.randomUUID();
  private static final UUID ORIGINAL = UUID.randomUUID();
  private static final UUID NEVER_READ = UUID.randomUUID();

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Decisions decisions;
  @Autowired Grounding grounding;
  @Autowired WebApplicationContext web;

  @Test
  void a_cited_id_the_agent_never_read_is_ungrounded() throws Exception {
    erp.on(
        "GET",
        "/api/invoices/" + ORIGINAL,
        200,
        "{\"invoice\":{\"id\":\""
            + ORIGINAL
            + "\",\"poNumber\":\"PO-12\",\"status\":\"MATCHED\"},\"exceptions\":[]}");
    model.script(
        steps(
            call("c1", "get_invoice", "{\"invoiceId\":\"" + ORIGINAL + "\"}"),
            call(
                "c2",
                "propose_resolution",
                "{\"action\":\"reject\",\"rationale\":\"a repeat\",\"evidence\":[\""
                    + INVOICE
                    + "\",\""
                    + ORIGINAL
                    + "\",\""
                    + NEVER_READ
                    + "\",\"PO-1\"]}")));
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-1",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.DUPLICATE,
            "s",
            new BigDecimal("100.00"));
    caseIndex.open(raised);
    AgentId agentId = caseIndex.agentFor(exceptionId);

    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));
    PendingDecision proposed =
        await()
            .atMost(Duration.ofSeconds(20))
            .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
            .getFirst();

    // Only what a tool returned counts: not the opening message, not the agent's own words, and
    // never a fragment of a longer id.
    assertThat(proposed.evidence()).hasSize(4);
    assertThat(grounding.ungrounded(agentId, proposed.evidence()))
        .containsExactly(INVOICE.toString(), NEVER_READ.toString(), "PO-1");
    MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    mvc.perform(get("/api/cases/{id}", exceptionId).with(bearer("connie", "controller")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decisions[0].evidence.length()").value(4))
        .andExpect(jsonPath("$.decisions[0].ungrounded[1]").value(NEVER_READ.toString()));
    mvc.perform(
            get("/workbench/cases/{id}", exceptionId)
                .with(
                    oidcLogin()
                        .idToken(t -> t.claim("preferred_username", "connie").subject("connie"))
                        .authorities(new SimpleGrantedAuthority("ROLE_controller"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("never read")))
        .andExpect(content().string(containsString(NEVER_READ.toString())));
  }

  private static JwtRequestPostProcessor bearer(String username, String role) {
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
}
