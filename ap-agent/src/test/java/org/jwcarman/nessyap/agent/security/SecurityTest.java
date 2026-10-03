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
package org.jwcarman.nessyap.agent.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class SecurityTest extends ApAgentIntegrationTest {

  @Autowired WebApplicationContext web;

  private MockMvc mvc;

  @BeforeEach
  void throughTheSecurityFilters() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
  }

  @Test
  void the_workbench_sends_a_stranger_to_log_in() throws Exception {
    mvc.perform(get("/workbench"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", containsString("/oauth2/authorization/keycloak")));
  }

  @Test
  void the_api_refuses_a_caller_without_a_token() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void the_api_knows_who_a_bearer_is_and_their_roles() throws Exception {
    mvc.perform(
            get("/api/me")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("connie")
                                    .claim("preferred_username", "connie")
                                    .claim("realm_access", Map.of("roles", List.of("controller"))))
                        .authorities(RealmRoles::authorities)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("connie"))
        .andExpect(jsonPath("$.roles").value(hasItem("controller")));
  }

  @Test
  void health_stays_open() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void a_case_is_not_readable_without_a_token() throws Exception {
    mvc.perform(get("/api/cases/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  @Test
  void a_case_is_readable_by_anyone_who_works_cases() throws Exception {
    mvc.perform(
            get("/api/cases/{id}", UUID.randomUUID())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("mark")
                                    .claim("realm_access", Map.of("roles", List.of("ap-manager"))))
                        .authorities(RealmRoles::authorities)))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_case_is_not_readable_by_someone_with_no_ap_role() throws Exception {
    mvc.perform(
            get("/api/cases/{id}", UUID.randomUUID())
                .with(
                    jwt()
                        .jwt(token -> token.subject("nobody"))
                        .authorities(RealmRoles::authorities)))
        .andExpect(status().isForbidden());
  }
}
