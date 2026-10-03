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
package org.jwcarman.nessyap.erp.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.erp.po.PoLine;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;

class MatchEngineTest {

  private static final UUID INVOICE = UUID.fromString("00000000-0000-7000-8000-000000000001");
  private static final UUID VENDOR = UUID.fromString("00000000-0000-7000-8000-000000000002");
  private static final UUID OTHER_VENDOR = UUID.fromString("00000000-0000-7000-8000-000000000003");
  private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
  private static final MatchEngine ENGINE = new MatchEngine(Tolerances.defaults());

  private static PurchaseOrder po(UUID vendor) {
    return new PurchaseOrder(
        UUID.fromString("00000000-0000-7000-8000-000000000004"),
        "PO-1",
        vendor,
        "bob",
        Instant.EPOCH,
        List.of(new PoLine(1, "M8 bolts", new BigDecimal("100"), new BigDecimal("10.00"))));
  }

  private static MatchLine line(Integer poLineNo, String quantity, String price) {
    return new MatchLine(1, poLineNo, new BigDecimal(quantity), new BigDecimal(price));
  }

  private static BigDecimal totalOf(List<MatchLine> lines, BigDecimal freight) {
    return lines.stream()
        .map(l -> l.quantity().multiply(l.unitPrice()))
        .reduce(freight, BigDecimal::add);
  }

  /** The base case: one PO line of 100 at 10.00, all received, billed exactly. */
  private static Case base() {
    return new Case(
        "INV-1001",
        "PO-1",
        BigDecimal.ZERO,
        List.of(line(1, "100", "10.00")),
        po(VENDOR),
        Map.of(1, new BigDecimal("100")),
        false,
        List.of());
  }

  /** A MatchInput under construction; each with* returns a changed copy. */
  private record Case(
      String number,
      String poNumber,
      BigDecimal freight,
      List<MatchLine> lines,
      PurchaseOrder po,
      Map<Integer, BigDecimal> received,
      boolean bankChange,
      List<PriorInvoice> priors) {

    Case withLines(MatchLine... newLines) {
      return new Case(
          number, poNumber, freight, List.of(newLines), po, received, bankChange, priors);
    }

    Case withReceived(Map<Integer, BigDecimal> newReceived) {
      return new Case(number, poNumber, freight, lines, po, newReceived, bankChange, priors);
    }

    Case withPo(PurchaseOrder newPo) {
      return new Case(number, poNumber, freight, lines, newPo, received, bankChange, priors);
    }

    Case withFreight(String newFreight) {
      return new Case(
          number, poNumber, new BigDecimal(newFreight), lines, po, received, bankChange, priors);
    }

    Case withBankChange() {
      return new Case(number, poNumber, freight, lines, po, received, true, priors);
    }

    Case withPriors(PriorInvoice... newPriors) {
      return new Case(
          number, poNumber, freight, lines, po, received, bankChange, List.of(newPriors));
    }

    List<MatchFinding> match() {
      return ENGINE.match(
          new MatchInput(
              INVOICE,
              number,
              DATE,
              VENDOR,
              poNumber,
              freight,
              totalOf(lines, freight),
              lines,
              po,
              received,
              bankChange,
              priors));
    }
  }

  private static PriorInvoice prior(String number, String poNumber, LocalDate date, String total) {
    return new PriorInvoice(
        UUID.fromString("00000000-0000-7000-8000-000000000009"),
        number,
        poNumber,
        date,
        new BigDecimal(total));
  }

  @Nested
  class When_everything_agrees {

    @Test
    void finds_nothing() {
      assertThat(base().match()).isEmpty();
    }
  }

  @Nested
  class When_the_price_is_over_tolerance {

    @Test
    void flags_a_price_variance_with_what_it_costs() {
      List<MatchFinding> findings = base().withLines(line(1, "100", "10.40")).match();

      assertThat(findings)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.PRICE_VARIANCE);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("40.00");
                assertThat(f.summary())
                    .isEqualTo("Line 1 billed 10.40 against PO price 10.00 (+4.00%)");
              });
    }

    @Test
    void stays_quiet_at_exactly_the_tolerance() {
      assertThat(base().withLines(line(1, "100", "10.20")).match()).isEmpty();
    }

    @Test
    void flags_one_cent_over_the_tolerance() {
      assertThat(base().withLines(line(1, "100", "10.21")).match())
          .singleElement()
          .satisfies(f -> assertThat(f.amountAtIssue()).isEqualByComparingTo("21.00"));
    }

    @Test
    void ignores_a_price_below_the_po() {
      assertThat(base().withLines(line(1, "100", "9.50")).match()).isEmpty();
    }
  }

  @Nested
  class When_receipts_fall_short {

    @Test
    void flags_quantity_over_receipt_for_what_has_not_arrived() {
      List<MatchFinding> findings =
          base().withReceived(Map.of(1, new BigDecimal("60.000"))).match();

      assertThat(findings)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.QTY_OVER_RECEIPT);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("400.00");
                assertThat(f.summary()).isEqualTo("Line 1 billed 100 but 60 received");
              });
    }

    @Test
    void counts_every_line_billing_the_same_po_line_against_one_receipt() {
      List<MatchFinding> findings =
          base()
              .withLines(
                  new MatchLine(1, 1, new BigDecimal("100"), new BigDecimal("10.00")),
                  new MatchLine(2, 1, new BigDecimal("100"), new BigDecimal("10.00")))
              .match();

      assertThat(findings)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.QTY_OVER_RECEIPT);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("1000.00");
                assertThat(f.summary()).isEqualTo("Line 2 billed 100 but 0 received");
              });
    }

    @Test
    void flags_no_receipt_when_nothing_arrived() {
      assertThat(base().withReceived(Map.of()).match())
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.NO_RECEIPT);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("1000.00");
              });
    }
  }

  @Nested
  class When_there_is_no_purchase_order {

    @Test
    void flags_no_po_and_checks_nothing_else_against_it() {
      List<MatchFinding> findings = base().withPo(null).withFreight("50.00").match();

      assertThat(findings)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.NO_PO);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("1050.00");
                assertThat(f.summary()).isEqualTo("No purchase order PO-1 exists");
              });
    }

    @Test
    void flags_a_purchase_order_that_belongs_to_another_vendor() {
      assertThat(base().withPo(po(OTHER_VENDOR)).match())
          .extracting(MatchFinding::code)
          .containsExactly(ReasonCode.NO_PO);
    }
  }

  @Nested
  class When_charges_are_not_on_the_po {

    @Test
    void flags_freight() {
      assertThat(base().withFreight("85.00").match())
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.UNPLANNED_CHARGE);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("85.00");
              });
    }

    @Test
    void flags_a_line_naming_a_po_line_that_does_not_exist() {
      assertThat(base().withLines(line(9, "3", "7.00")).match())
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.code()).isEqualTo(ReasonCode.UNPLANNED_CHARGE);
                assertThat(f.amountAtIssue()).isEqualByComparingTo("21.00");
              });
    }

    @Test
    void flags_a_line_citing_no_po_line() {
      assertThat(
              base()
                  .withLines(
                      line(1, "100", "10.00"),
                      new MatchLine(2, null, BigDecimal.ONE, BigDecimal.TEN))
                  .match())
          .extracting(MatchFinding::code)
          .containsExactly(ReasonCode.UNPLANNED_CHARGE);
    }
  }

  @Nested
  class When_the_invoice_repeats_an_earlier_one {

    @Test
    void flags_the_same_number_written_differently() {
      assertThat(base().withPriors(prior("inv1001", null, DATE.minusDays(40), "1.00")).match())
          .extracting(MatchFinding::code)
          .containsExactly(ReasonCode.DUPLICATE);
    }

    @Test
    void flags_the_same_po_and_total_within_a_week() {
      assertThat(base().withPriors(prior("INV-0999", "PO-1", DATE.minusDays(5), "1000.00")).match())
          .extracting(MatchFinding::code)
          .containsExactly(ReasonCode.DUPLICATE);
    }

    @Test
    void ignores_the_same_po_and_total_a_month_apart() {
      assertThat(
              base().withPriors(prior("INV-0999", "PO-1", DATE.minusDays(30), "1000.00")).match())
          .isEmpty();
    }

    @Test
    void ignores_itself() {
      PriorInvoice itself =
          new PriorInvoice(INVOICE, "INV-1001", "PO-1", DATE, new BigDecimal("1000.00"));

      assertThat(base().withPriors(itself).match()).isEmpty();
    }
  }

  @Nested
  class When_the_vendors_bank_details_changed {

    @Test
    void flags_an_unverified_change_on_an_otherwise_clean_invoice() {
      assertThat(base().withBankChange().match())
          .extracting(MatchFinding::code)
          .containsExactly(ReasonCode.VENDOR_BANK_CHANGED);
    }
  }

  @Test
  void normalizes_invoice_numbers_to_letters_and_digits() {
    assertThat(InvoiceNumbers.normalize("inv-10 01/a")).isEqualTo("INV1001A");
  }
}
