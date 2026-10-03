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
package org.jwcarman.nessyap.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErpEventsTest {

  private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

  @Test
  void a_raised_exception_routes_as_match_exception_raised() {
    ErpEvent event =
        new MatchExceptionRaised(
            ID, Instant.EPOCH, ID, ID, "INV-1", ID, "PO-1", ReasonCode.NO_PO, "s", BigDecimal.ONE);
    assertThat(ErpEvents.routingKey(event)).isEqualTo("match-exception.raised");
  }

  @Test
  void a_posted_receipt_routes_as_receipt_posted() {
    assertThat(ErpEvents.routingKey(new ReceiptPosted(ID, Instant.EPOCH, ID, "PO-1")))
        .isEqualTo("receipt.posted");
  }

  @Test
  void a_resolved_invoice_routes_as_invoice_resolved() {
    assertThat(ErpEvents.routingKey(new InvoiceResolved(ID, Instant.EPOCH, ID, "hold", "ON_HOLD")))
        .isEqualTo("invoice.resolved");
  }

  @Test
  void a_proposed_bank_change_routes_as_vendor_bank_change_proposed() {
    assertThat(ErpEvents.routingKey(new VendorBankChangeProposed(ID, Instant.EPOCH, ID, ID)))
        .isEqualTo("vendor.bank-change.proposed");
  }

  @Test
  void every_event_goes_to_the_one_exchange() {
    assertThat(ErpEvents.EXCHANGE).isEqualTo("erp.events");
  }
}
