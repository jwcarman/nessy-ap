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
package org.jwcarman.nessyap.erp.resolution;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ResolutionApiTest extends ErpIntegrationTest {

  @Autowired WebApplicationContext web;

  private MockMvc mvc;
  private Invoice overpriced;

  @BeforeEach
  void anInvoiceInException() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(
                post("/")
                    .with(
                        jwt()
                            .jwt(
                                token ->
                                    token
                                        .subject("connie")
                                        .claim("azp", "workbench")
                                        .claim("preferred_username", "connie"))))
            .build();
    Vendor acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    overpriced = data().invoice(acme, "INV-1001", "PO-1", "100", "10.40");
  }

  @Test
  void a_hold_answers_with_the_held_invoice() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/hold", overpriced.id())
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": 1, \"comment\": \"waiting on the buyer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ON_HOLD"))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void a_command_without_an_idempotency_key_is_a_400() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/hold", overpriced.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": 1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void a_stale_version_is_a_409_problem() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/hold", overpriced.id())
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": 0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_VERSION"));
  }

  @Test
  void an_unknown_action_is_a_404() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/pay-twice", overpriced.id())
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": 1}"))
        .andExpect(status().isNotFound());
  }
}
