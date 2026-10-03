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
package org.jwcarman.nessyap.erp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The ERP checks every token it is handed: signature, issuer, audience. */
class ErpSecurityTest extends ErpIntegrationTest {

  private static final SignedTokens TOKENS = new SignedTokens();

  @DynamicPropertySource
  static void identityProvider(DynamicPropertyRegistry registry) {
    registry.add("erp.security.jwk-set-uri", TOKENS::jwkSetUri);
    registry.add("erp.security.issuer", () -> SignedTokens.ISSUER);
  }

  @AfterAll
  static void stop() {
    TOKENS.close();
  }

  @Autowired WebApplicationContext web;

  private MockMvc mvc;

  @BeforeEach
  void throughTheFilters() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
  }

  @Test
  void no_token_is_refused() throws Exception {
    mvc.perform(get("/api/vendors/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  @Test
  void a_token_meant_for_someone_else_is_refused() throws Exception {
    mvc.perform(
            get("/api/vendors/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + TOKENS.user("clara", "workbench", "account")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void the_agents_own_token_can_read() throws Exception {
    Vendor vendor = data().vendor();

    mvc.perform(
            get("/api/vendors/{id}", vendor.id())
                .header("Authorization", "Bearer " + TOKENS.service("ap-agent-service")))
        .andExpect(status().isOk());
  }

  @Test
  void admin_and_health_need_no_token() throws Exception {
    mvc.perform(get("/admin/scenarios")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void the_audit_says_which_client_acted_for_which_person() throws Exception {
    Vendor acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.40");

    mvc.perform(
            post("/api/invoices/{id}/hold", invoice.id())
                .header("Authorization", "Bearer " + TOKENS.user("connie", "workbench", "erp-sim"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": 1}"))
        .andExpect(status().isOk());

    Map<String, Object> audit =
        jdbc.sql(
                "select acting_client, acting_user from erp_audit where entity_id = :id and action = 'hold'")
            .param("id", invoice.id())
            .query()
            .singleRow();
    assertThat(audit)
        .containsEntry("acting_client", "workbench")
        .containsEntry("acting_user", "connie");
  }
}
