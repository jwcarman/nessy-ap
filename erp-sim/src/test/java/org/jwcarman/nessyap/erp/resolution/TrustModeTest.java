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
package org.jwcarman.nessyap.erp.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The common weak deployment, reproduced on purpose: the ERP trusts its integration caller and
 * records who it says decided without checking. These tests document what that costs.
 */
@TestPropertySource(properties = "erp.authority.mode=trust-integration-user")
class TrustModeTest extends ErpIntegrationTest {

  private static final JwtRequestPostProcessor INTEGRATION =
      jwt()
          .jwt(
              token ->
                  token
                      .subject("service-account-ap-agent-service")
                      .claim("azp", "ap-agent-service")
                      .claim("preferred_username", "service-account-ap-agent-service"));

  @Autowired WebApplicationContext web;

  private MockMvc mvc;
  private Invoice big;

  @BeforeEach
  void aBigInvoice() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    Vendor acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    big = data().invoice(acme, "INV-1", "PO-1", "5000", "10.40");
  }

  @Test
  void a_clerks_name_on_the_integration_users_call_approves_fifty_thousand() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/approve-variance", big.id())
                .with(INTEGRATION)
                .header("X-Acting-User", "clara")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": " + big.version() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    Map<String, Object> audit =
        jdbc.sql(
                "select acting_client, acting_user from erp_audit"
                    + " where entity_id = :id and action = 'approve-variance'")
            .param("id", big.id())
            .query()
            .singleRow();
    assertThat(audit)
        .containsEntry("acting_client", "ap-agent-service")
        .containsEntry("acting_user", "clara");
  }

  @Test
  void a_command_naming_nobody_is_still_refused() throws Exception {
    mvc.perform(
            post("/api/invoices/{id}/hold", big.id())
                .with(INTEGRATION)
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\": " + big.version() + "}"))
        .andExpect(status().isBadRequest());
  }
}
