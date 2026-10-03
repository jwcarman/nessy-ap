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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class VendorChangesTest extends ApAgentIntegrationTest {

  private static final UUID VENDOR = UUID.fromString("01a0ffe1-29e1-710c-982b-cd888289bdae");
  private static final UUID ACCOUNT = UUID.fromString("01a0ffe1-0000-7000-8000-00000000abcd");

  @Autowired WebApplicationContext web;
  @Autowired Cases cases;

  private MockMvc mvc;

  private static OidcLoginRequestPostProcessor as(String username, String role) {
    return oidcLogin()
        .idToken(token -> token.claim("preferred_username", username).subject(username))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @BeforeEach
  void aVendorWithAPendingChange() {
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    cases.open(
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "INV-1",
            VENDOR,
            "PO-1",
            ReasonCode.VENDOR_BANK_CHANGED,
            "s",
            BigDecimal.TEN));
    erp.on(
        "GET",
        "/api/vendors/" + VENDOR,
        200,
        "{\"id\":\""
            + VENDOR
            + "\",\"name\":\"Acme Fasteners\","
            + "\"contact\":{\"name\":\"Ada Acme\",\"phone\":\"+1-555-0100\",\"email\":\"ar@acme.example\"},"
            + "\"bankAccounts\":[{\"id\":\""
            + ACCOUNT
            + "\",\"accountNumber\":\"998877665\","
            + "\"status\":\"PENDING_VERIFICATION\",\"proposedByEmail\":\"accounts@acme-billing.example\"}]}");
  }

  @Test
  void the_page_lists_a_pending_change_with_the_number_to_call() throws Exception {
    mvc.perform(get("/workbench/vendors").with(as("mark", "ap-manager")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Acme Fasteners")))
        .andExpect(content().string(containsString("+1-555-0100")))
        .andExpect(content().string(containsString("accounts@acme-billing.example")));
  }

  @Test
  void recording_a_call_back_goes_to_the_erp_as_the_caller() throws Exception {
    erp.on("POST", "/api/vendors/" + VENDOR + "/bank-changes/" + ACCOUNT + "/call-back", 200, "{}");

    mvc.perform(
            post("/workbench/vendors/{v}/bank-changes/{a}/call-back", VENDOR, ACCOUNT)
                .param("phone", "+1-555-0100")
                .param("vendorConfirmed", "true")
                .with(as("mark", "ap-manager"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    assertThat(erp.seen())
        .filteredOn(seen -> seen.target().endsWith("/call-back"))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.header("Authorization")).startsWith("Bearer ");
              assertThat(call.body()).contains("+1-555-0100").contains("\"vendorConfirmed\":true");
            });
  }

  @Test
  void a_clerk_cannot_verify_bank_changes() throws Exception {
    mvc.perform(
            post("/workbench/vendors/{v}/bank-changes/{a}/confirm", VENDOR, ACCOUNT)
                .with(as("clara", "ap-clerk"))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }
}
