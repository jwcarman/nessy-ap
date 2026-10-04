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
package org.jwcarman.nessyap.erp.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.TestData;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.matching.ExceptionStatus;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;

class InvoiceIntakeTest extends ErpIntegrationTest {

  private static final Actor SYSTEM = Actor.system();

  @Autowired InvoiceIntake intake;
  @Autowired MatchExceptionRepository exceptions;

  private Vendor acme;

  @BeforeEach
  void aReceivedOrder() {
    acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
  }

  private long raisedEvents() {
    return jdbc.sql("select count(*) from outbox where event_type = 'match-exception.raised'")
        .query(Long.class)
        .single();
  }

  @Test
  void a_clean_invoice_is_matched() {
    Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.00");

    assertThat(invoice.status()).isEqualTo(InvoiceStatus.MATCHED);
    assertThat(invoice.version()).isEqualTo(1);
    assertThat(exceptions.findByInvoice(invoice.id())).isEmpty();
    assertThat(raisedEvents()).isZero();
  }

  @Test
  void a_price_variance_raises_one_open_exception_and_tells_the_world() {
    Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.40");

    assertThat(invoice.status()).isEqualTo(InvoiceStatus.EXCEPTION);
    assertThat(exceptions.findByInvoice(invoice.id()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.reasonCode()).isEqualTo(ReasonCode.PRICE_VARIANCE);
              assertThat(e.status()).isEqualTo(ExceptionStatus.OPEN);
              assertThat(e.amountAtIssue()).isEqualByComparingTo("40.00");
            });
    assertThat(
            jdbc.sql("select payload::text from outbox where event_type = 'match-exception.raised'")
                .query(String.class)
                .single())
        .contains("\"reasonCode\": \"PRICE_VARIANCE\"")
        .contains("\"invoiceId\": \"" + invoice.id() + "\"");
  }

  @Test
  void an_unknown_po_number_raises_no_po() {
    Invoice invoice = data().invoice(acme, "INV-1001", "PO-NOPE", "100", "10.00");

    assertThat(exceptions.findByInvoice(invoice.id()))
        .extracting(e -> e.reasonCode())
        .containsExactly(ReasonCode.NO_PO);
  }

  @Test
  void the_same_number_written_differently_is_a_duplicate() {
    data().invoice(acme, "INV-1001", "PO-1", "100", "10.00");

    Invoice second = data().invoice(acme, "INV 1001", "PO-1", "100", "10.00");

    assertThat(exceptions.findByInvoice(second.id()))
        .extracting(e -> e.reasonCode())
        .containsExactly(ReasonCode.DUPLICATE);
  }

  @Test
  void the_total_includes_tax_and_freight() {
    Invoice invoice =
        intake.receive(
            SYSTEM,
            new NewInvoice(
                acme.id(),
                "INV-2002",
                "PO-1",
                TestData.INVOICE_DATE,
                new BigDecimal("1.60"),
                new BigDecimal("5.00"),
                List.of(
                    new InvoiceLine(
                        1, 1, "M8 bolts", new BigDecimal("2"), new BigDecimal("10.00")))));

    assertThat(invoice.total()).isEqualByComparingTo("26.60");
  }

  @Test
  void an_unknown_vendor_is_not_found() {
    NewInvoice orphan =
        new NewInvoice(
            Ids.next(),
            "INV-1",
            "PO-1",
            TestData.INVOICE_DATE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of(new InvoiceLine(1, 1, "x", BigDecimal.ONE, BigDecimal.ONE)));

    assertThatThrownBy(() -> intake.receive(SYSTEM, orphan)).isInstanceOf(NotFoundException.class);
  }
}
