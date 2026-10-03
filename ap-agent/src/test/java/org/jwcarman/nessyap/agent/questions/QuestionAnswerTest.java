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
package org.jwcarman.nessyap.agent.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The person asked answers on the workbench, or through the API; nobody else can. */
class QuestionAnswerTest extends ApAgentIntegrationTest {

  @Autowired WebApplicationContext web;
  @Autowired Questions questions;

  private MockMvc mvc;
  private UUID exceptionId;
  private Question question;

  private static OidcLoginRequestPostProcessor as(String username, String role) {
    return oidcLogin()
        .idToken(token -> token.claim("preferred_username", username).subject(username))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
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

  @BeforeEach
  void aQuestionForBob() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    exceptionId = openCase();
    question =
        questions.ask(
            exceptionId,
            "bob",
            "Did you agree the unit price of 11.60?",
            List.of("Agreed", "Not agreed"));
  }

  @Test
  void bob_sees_the_question_on_his_worklist_and_answers_it_there() throws Exception {
    mvc.perform(get("/workbench").with(as("bob", "buyer")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Did you agree the unit price of 11.60?")));

    mvc.perform(
            post("/workbench/questions/{id}/answer", question.id())
                .param("choice", "Agreed")
                .param("comment", "By phone in March.")
                .with(as("bob", "buyer"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    assertThat(questions.forCase(exceptionId))
        .singleElement()
        .satisfies(
            q -> {
              assertThat(q.choice()).isEqualTo("Agreed");
              assertThat(q.comment()).isEqualTo("By phone in March.");
            });
    mvc.perform(get("/workbench").with(as("bob", "buyer")))
        .andExpect(
            content()
                .string(not(containsString("/workbench/questions/" + question.id() + "/answer"))));
  }

  @Test
  void another_buyer_may_not_answer_bobs_question() throws Exception {
    mvc.perform(
            post("/api/questions/{id}/answer", question.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"Agreed\"}")
                .with(bearer("betty", "buyer")))
        .andExpect(status().isForbidden());

    assertThat(questions.forCase(exceptionId)).singleElement().matches(q -> !q.answered());
  }

  @Test
  void a_second_answer_is_refused_and_a_choice_not_offered_is_a_bad_request() throws Exception {
    mvc.perform(
            post("/api/questions/{id}/answer", question.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"Maybe\"}")
                .with(bearer("bob", "buyer")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/questions/{id}/answer", question.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"Agreed\"}")
                .with(bearer("bob", "buyer")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/questions/{id}/answer", question.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"Not agreed\"}")
                .with(bearer("bob", "buyer")))
        .andExpect(status().isConflict());
  }

  @Test
  void the_case_view_lists_the_question_and_its_answer() throws Exception {
    mvc.perform(
            post("/api/questions/{id}/answer", question.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Yes, agreed.\"}")
                .with(bearer("bob", "buyer")))
        .andExpect(status().isOk());

    mvc.perform(get("/api/cases/{id}", exceptionId).with(bearer("connie", "controller")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questions[0].askedOf").value("bob"))
        .andExpect(jsonPath("$.questions[0].comment").value("Yes, agreed."));
  }
}
