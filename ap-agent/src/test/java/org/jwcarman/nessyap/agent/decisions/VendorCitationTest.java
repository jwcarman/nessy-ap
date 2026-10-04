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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A hold over an unverified bank change must cite the vendor. Measured: a model held correctly and
 * cited the invoice, a receipt and the PO, but not the vendor, in 5 of 20 runs.
 */
class VendorCitationTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  private final UUID vendor = UUID.randomUUID();
  private final UUID pending = UUID.randomUUID();
  private final UUID invoice = UUID.randomUUID();

  @BeforeEach
  void aVendorWithAnUnverifiedBankChange() {
    erp.on(
        "GET",
        "/api/invoices/" + invoice,
        200,
        "{\"invoice\":{\"id\":\""
            + invoice
            + "\",\"total\":1000.00,\"status\":\"EXCEPTION\"},\"exceptions\":[]}");
    erp.on(
        "GET",
        "/api/vendors/" + vendor,
        200,
        "{\"id\":\""
            + vendor
            + "\",\"bankAccounts\":[{\"id\":\""
            + pending
            + "\",\"status\":\"PENDING_VERIFICATION\"}]}");
  }

  private UUID holdCiting(String... ids) {
    model.script(
        steps(
            call("c1", "get_invoice", "{}"),
            call("c2", "get_vendor", "{}"),
            call(
                "c3",
                "propose_resolution",
                "{\"action\":\"hold\",\"rationale\":\"The bank change is unverified.\","
                    + "\"evidence\":[\""
                    + String.join("\",\"", ids)
                    + "\"]}")));
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            invoice,
            "INV-1",
            vendor,
            null,
            ReasonCode.VENDOR_BANK_CHANGED,
            "s",
            BigDecimal.ZERO);
    cases.open(raised);
    agent.tell(cases.agentFor(exceptionId), new CaseInput.ExceptionRaised(raised));
    return exceptionId;
  }

  @Test
  void a_hold_that_cites_only_the_invoice_is_refused_naming_the_vendor_but_no_id() {
    UUID exceptionId = holdCiting(invoice.toString());

    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(model.outcomesSeen())
                    .filteredOn(ToolOutcome.Denied.class::isInstance)
                    .singleElement()
                    .satisfies(
                        o ->
                            assertThat(((ToolOutcome.Denied) o).reason())
                                .contains("cite the vendor")
                                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}")));
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }

  @Test
  void a_hold_that_cites_the_vendor_goes_to_the_clerk() {
    UUID exceptionId = holdCiting(invoice.toString(), vendor.toString());

    await().atMost(PATIENCE).until(() -> !decisions.forCase(exceptionId).isEmpty());
    assertThat(model.outcomesSeen()).noneMatch(ToolOutcome.Denied.class::isInstance);
  }

  @Test
  void a_hold_that_cites_the_pending_bank_change_goes_to_the_clerk() {
    UUID exceptionId = holdCiting(invoice.toString(), pending.toString());

    await().atMost(PATIENCE).until(() -> !decisions.forCase(exceptionId).isEmpty());
    assertThat(model.outcomesSeen()).noneMatch(ToolOutcome.Denied.class::isInstance);
  }
}
