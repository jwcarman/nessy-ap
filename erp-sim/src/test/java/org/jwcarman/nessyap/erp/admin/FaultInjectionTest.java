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
package org.jwcarman.nessyap.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class FaultInjectionTest extends ErpIntegrationTest {

  @Autowired WebApplicationContext web;
  @Autowired FaultInjectionFilter filter;
  @Autowired FaultRules rules;

  private MockMvc mvc;
  private Vendor acme;

  @BeforeEach
  void aClientThroughTheFilter() {
    rules.clear();
    // A hand-built MockMvc only runs the servlet filters it is given.
    mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(filter).build();
    acme = data().vendor();
  }

  private void putRule(String pattern, long latencyMillis, double errorRate) throws Exception {
    mvc.perform(
            put("/admin/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"pathPattern": "%s", "latencyMillis": %d, "errorRate": %s}
                    """
                        .formatted(pattern, latencyMillis, errorRate)))
        .andExpect(status().isNoContent());
  }

  @Test
  void a_certain_failure_answers_503_with_a_code() throws Exception {
    putRule("/api/vendors/**", 0, 1.0);

    mvc.perform(get("/api/vendors/{id}", acme.id()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("INJECTED_FAULT"));
  }

  @Test
  void a_rate_limit_answers_429_and_says_when_to_try_again() throws Exception {
    mvc.perform(
            put("/admin/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"pathPattern": "/api/vendors/**", "latencyMillis": 0, "errorRate": 1.0,
                     "status": 429}
                    """))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/vendors/{id}", acme.id()))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void a_rule_touches_only_the_paths_it_names() throws Exception {
    putRule("/api/vendors/**", 0, 1.0);

    mvc.perform(get("/api/match-exceptions")).andExpect(status().isOk());
  }

  @Test
  void admin_is_never_faulted_so_faults_can_always_be_cleared() throws Exception {
    putRule("/api/**", 0, 1.0);

    mvc.perform(get("/admin/faults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].pathPattern").value("/api/**"));
  }

  @Test
  void latency_slows_the_request_down() throws Exception {
    putRule("/api/vendors/**", 300, 0.0);

    long started = System.nanoTime();
    mvc.perform(get("/api/vendors/{id}", acme.id())).andExpect(status().isOk());

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(Duration.ofMillis(300));
  }

  @Test
  void an_error_rate_above_one_is_refused() throws Exception {
    mvc.perform(
            put("/admin/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pathPattern\": \"/api/**\", \"latencyMillis\": 0, \"errorRate\": 2}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void a_rule_outside_the_api_is_refused() throws Exception {
    mvc.perform(
            put("/admin/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"pathPattern\": \"/admin/**\", \"latencyMillis\": 0, \"errorRate\": 1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void clearing_the_rules_restores_service() throws Exception {
    putRule("/api/vendors/**", 0, 1.0);

    mvc.perform(delete("/admin/faults")).andExpect(status().isNoContent());

    mvc.perform(get("/api/vendors/{id}", acme.id())).andExpect(status().isOk());
  }
}
