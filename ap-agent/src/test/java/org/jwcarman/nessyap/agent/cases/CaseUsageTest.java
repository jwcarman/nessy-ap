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
package org.jwcarman.nessyap.agent.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A case's usage is every agent's that worked it, summed within each model and never across. */
class CaseUsageTest extends ApAgentIntegrationTest {

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired CaseUsage usage;
  @Autowired WebApplicationContext web;

  @Test
  void every_agent_on_the_case_counts_once_and_each_model_stays_its_own() throws Exception {
    model.script(
        request ->
            new InferenceResult.Answer(
                List.of(new Block.Text("Noted.")), Usage.of("qwen", 100, 10)));
    UUID exceptionId = openCase();
    AgentId desk = caseIndex.agentFor(exceptionId);
    AgentId helper = new AgentId(UUID.randomUUID());
    caseIndex.addAgent(exceptionId, AgentConfiguration.AGENT_TYPE, helper);
    caseIndex.addAgent(exceptionId, AgentConfiguration.AGENT_TYPE, helper);

    agent.tell(desk, new CaseInput.PersonNote("clara", "look"));
    agent.tell(helper, new CaseInput.PersonNote("clara", "look too"));
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                narration.count(desk, Narration.TurnEnding.class) == 1
                    && narration.count(helper, Narration.TurnEnding.class) == 1);

    CaseUsage.Spent spent = usage.of(exceptionId);

    assertThat(spent.byModel())
        .containsExactly(
            new ModelUsage(
                "qwen",
                2,
                Tokens.of(200),
                Tokens.of(20),
                Tokens.none(),
                Tokens.none(),
                Tokens.none()));
    assertThat(spent.unreported()).isZero();
    MockMvcBuilders.webAppContextSetup(web)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build()
        .perform(
            get("/api/cases/{id}/usage", exceptionId)
                .with(
                    jwt()
                        .jwt(
                            t ->
                                t.claim("preferred_username", "connie")
                                    .claim("realm_access", Map.of("roles", List.of("controller"))))
                        .authorities(RealmRoles::authorities)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.byModel[0].model").value("qwen"))
        .andExpect(jsonPath("$.byModel[0].inferences").value(2))
        .andExpect(jsonPath("$.byModel[0].input").value(200));
  }
}
