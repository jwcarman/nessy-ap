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
package org.jwcarman.nessyap.agent.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ErpClientTest {

  private static final UUID ID = UUID.fromString("01a0ffe1-29f3-7457-88d0-bc59aa5c810b");

  private ErpStub erp;
  private ErpClient client;

  @BeforeEach
  void anErp() {
    erp = new ErpStub();
    client =
        new ErpClient(
            erp.baseUrl(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    erp.close();
  }

  @Nested
  class Reads {

    @Test
    void an_invoice_comes_back_as_json() {
      erp.on("GET", "/api/invoices/" + ID, 200, "{\"invoice\":{\"status\":\"EXCEPTION\"}}");

      ErpOutcome outcome = client.invoice(ID);

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ErpOutcome.Ok.class, ok -> assertThat(ok.value().toString()).contains("EXCEPTION"));
    }

    @Test
    void each_read_asks_the_right_path() {
      client.purchaseOrder("PO-1");
      client.receipts("PO-1");
      client.vendor(ID);
      client.vendorInvoices(ID);
      client.matchException(ID);
      client.similarInvoices(ID, "INV 1001", new BigDecimal("1040.00"));

      assertThat(erp.seen())
          .extracting(ErpStub.Seen::target)
          .containsExactly(
              "/api/purchase-orders/PO-1",
              "/api/purchase-orders/PO-1/receipts",
              "/api/vendors/" + ID,
              "/api/vendors/" + ID + "/invoices",
              "/api/match-exceptions/" + ID,
              "/api/invoices/similar?vendorId=" + ID + "&invoiceNumber=INV%201001&total=1040.00");
    }
  }

  @Nested
  class Trouble {

    @Test
    void a_problem_response_is_a_refusal_with_its_code() {
      erp.on(
          "GET",
          "/api/invoices/" + ID,
          404,
          "{\"status\":404,\"code\":\"NOT_FOUND\",\"detail\":\"No invoice\"}");

      assertThat(client.invoice(ID))
          .isEqualTo(new ErpOutcome.Refused(404, "NOT_FOUND", "No invoice"));
    }

    @Test
    void a_4xx_without_a_problem_body_is_still_a_refusal() {
      erp.on("GET", "/api/invoices/" + ID, 400, "nope");

      assertThat(client.invoice(ID)).isEqualTo(new ErpOutcome.Refused(400, "HTTP_400", "nope"));
    }

    @Test
    void a_5xx_is_unavailable() {
      erp.on(
          "GET",
          "/api/invoices/" + ID,
          503,
          "{\"status\":503,\"code\":\"INJECTED_FAULT\",\"detail\":\"Injected fault\"}");

      assertThat(client.invoice(ID)).isInstanceOf(ErpOutcome.Unavailable.class);
    }

    @Test
    void a_rate_limit_is_a_wait_not_a_refusal() {
      erp.on(
          "GET",
          "/api/invoices/" + ID,
          429,
          "{\"status\":429,\"code\":\"RATE_LIMITED\",\"detail\":\"Injected rate limit\"}");

      assertThat(client.invoice(ID))
          .isInstanceOfSatisfying(
              ErpOutcome.Unavailable.class,
              u -> assertThat(u.reason()).contains("rate limited").contains("retry"));
    }

    @Test
    void a_slow_erp_is_unavailable() {
      erp.on("GET", "/api/invoices/" + ID, 200, "{}", Duration.ofSeconds(3));

      assertThat(client.invoice(ID)).isInstanceOf(ErpOutcome.Unavailable.class);
    }

    @Test
    void an_erp_that_is_not_there_is_unavailable() {
      ErpClient nowhere =
          new ErpClient(
              "http://127.0.0.1:1",
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              JsonMapper.builder().build());

      assertThat(nowhere.invoice(ID)).isInstanceOf(ErpOutcome.Unavailable.class);
    }
  }

  @Test
  void reads_carry_the_agents_own_token_and_commands_only_the_deciders() {
    erp.on("POST", "/token", 200, "{\"access_token\":\"svc-1\",\"expires_in\":300}");
    erp.on("GET", "/api/invoices/" + ID, 200, "{}");
    erp.on("POST", "/api/invoices/" + ID + "/hold", 200, "{}");
    ServiceToken service =
        new ServiceToken(
            erp.baseUrl() + "/token",
            "ap-agent-service",
            "s",
            Clock.systemUTC(),
            Duration.ofSeconds(1),
            JsonMapper.builder().build());
    ErpClient signed =
        new ErpClient(
            erp.baseUrl(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            JsonMapper.builder().build(),
            service);

    signed.invoice(ID);
    signed.resolve(ID, "hold", "k", 1, null, null, null);
    signed.resolve(ID, "hold", "k2", 1, null, null, "person");

    assertThat(erp.seen())
        .filteredOn(seen -> seen.target().startsWith("/api/"))
        .extracting(seen -> seen.method() + " " + seen.header("Authorization"))
        .containsExactly("GET Bearer svc-1", "POST null", "POST Bearer person");
  }

  @Test
  void a_resolution_carries_its_idempotency_key_and_version() {
    erp.on("POST", "/api/invoices/" + ID + "/approve-variance", 200, "{\"status\":\"APPROVED\"}");

    ErpOutcome outcome = client.resolve(ID, "approve-variance", "decision-1", 3, null, "fine");

    assertThat(outcome).isInstanceOf(ErpOutcome.Ok.class);
    ErpStub.Seen sent = erp.seen().getFirst();
    assertThat(sent.header("Idempotency-Key")).isEqualTo("decision-1");
    assertThat(sent.body()).contains("\"expectedVersion\":3").contains("\"comment\":\"fine\"");
  }
}
