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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.erp.po.PoLine;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;

/**
 * The three-way match: invoice against purchase order against receipts. Pure: everything it needs
 * arrives in the {@link MatchInput}, and at most one finding comes back per reason.
 */
public final class MatchEngine {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final Tolerances tolerances;

  public MatchEngine(Tolerances tolerances) {
    this.tolerances = tolerances;
  }

  public List<MatchFinding> match(MatchInput in) {
    List<MatchFinding> findings = new ArrayList<>();
    duplicateOf(in).ifPresent(findings::add);
    if (in.vendorHasUnverifiedBankChange()) {
      findings.add(
          new MatchFinding(
              ReasonCode.VENDOR_BANK_CHANGED,
              "Vendor has a bank-detail change that has not been verified",
              money(in.total())));
    }
    PurchaseOrder po = in.purchaseOrder();
    if (po == null) {
      String summary =
          in.poNumber() == null
              ? "Invoice cites no purchase order"
              : "No purchase order " + in.poNumber() + " exists";
      findings.add(new MatchFinding(ReasonCode.NO_PO, summary, money(in.total())));
      return List.copyOf(findings);
    }
    if (!po.vendorId().equals(in.vendorId())) {
      findings.add(
          new MatchFinding(
              ReasonCode.NO_PO,
              "Purchase order " + po.poNumber() + " belongs to a different vendor",
              money(in.total())));
      return List.copyOf(findings);
    }
    priceVariance(in, po).ifPresent(findings::add);
    receiptShortfall(in, po).ifPresent(findings::add);
    unplannedCharges(in, po).ifPresent(findings::add);
    return List.copyOf(findings);
  }

  private Optional<MatchFinding> duplicateOf(MatchInput in) {
    String number = InvoiceNumbers.normalize(in.invoiceNumber());
    return in.priorInvoices().stream()
        .filter(prior -> !prior.id().equals(in.invoiceId()))
        .filter(
            prior ->
                InvoiceNumbers.normalize(prior.invoiceNumber()).equals(number)
                    || samePoAndTotalNearby(prior, in))
        .findFirst()
        .map(
            prior ->
                new MatchFinding(
                    ReasonCode.DUPLICATE,
                    "Looks like invoice "
                        + prior.invoiceNumber()
                        + " ("
                        + prior.id()
                        + "), received earlier",
                    money(in.total())));
  }

  private boolean samePoAndTotalNearby(PriorInvoice prior, MatchInput in) {
    return in.poNumber() != null
        && in.poNumber().equals(prior.poNumber())
        && prior.total().compareTo(in.total()) == 0
        && Math.abs(ChronoUnit.DAYS.between(prior.invoiceDate(), in.invoiceDate()))
            <= tolerances.duplicateWindowDays();
  }

  private Optional<MatchFinding> priceVariance(MatchInput in, PurchaseOrder po) {
    BigDecimal factor = BigDecimal.ONE.add(tolerances.pricePercent().divide(HUNDRED));
    BigDecimal impact = BigDecimal.ZERO;
    List<String> notes = new ArrayList<>();
    for (MatchLine line : in.lines()) {
      Optional<PoLine> poLine = poLineFor(line, po);
      if (poLine.isPresent()
          && line.unitPrice().compareTo(poLine.get().unitPrice().multiply(factor)) > 0) {
        BigDecimal poPrice = poLine.get().unitPrice();
        BigDecimal over = line.unitPrice().subtract(poPrice);
        impact = impact.add(over.multiply(line.quantity()));
        BigDecimal percent = over.multiply(HUNDRED).divide(poPrice, 2, RoundingMode.HALF_UP);
        notes.add(
            "Line "
                + line.lineNo()
                + " billed "
                + line.unitPrice().toPlainString()
                + " against PO price "
                + poPrice.toPlainString()
                + " (+"
                + percent.toPlainString()
                + "%)");
      }
    }
    return finding(ReasonCode.PRICE_VARIANCE, notes, impact);
  }

  private static Optional<MatchFinding> receiptShortfall(MatchInput in, PurchaseOrder po) {
    List<MatchLine> onPo = in.lines().stream().filter(l -> poLineFor(l, po).isPresent()).toList();
    if (onPo.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal receivedTotal =
        onPo.stream().map(l -> received(in, l)).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (receivedTotal.signum() == 0) {
      BigDecimal value =
          onPo.stream().map(MatchEngine::extended).reduce(BigDecimal.ZERO, BigDecimal::add);
      return Optional.of(
          new MatchFinding(
              ReasonCode.NO_RECEIPT,
              "Nothing has been received against purchase order " + po.poNumber(),
              money(value)));
    }
    BigDecimal impact = BigDecimal.ZERO;
    List<String> notes = new ArrayList<>();
    for (MatchLine line : onPo) {
      BigDecimal received = received(in, line);
      if (line.quantity().compareTo(received) > 0) {
        impact = impact.add(line.quantity().subtract(received).multiply(line.unitPrice()));
        notes.add(
            "Line "
                + line.lineNo()
                + " billed "
                + plain(line.quantity())
                + " but "
                + plain(received)
                + " received");
      }
    }
    return finding(ReasonCode.QTY_OVER_RECEIPT, notes, impact);
  }

  private static Optional<MatchFinding> unplannedCharges(MatchInput in, PurchaseOrder po) {
    BigDecimal charge = BigDecimal.ZERO;
    List<String> notes = new ArrayList<>();
    if (in.freight().signum() > 0) {
      charge = charge.add(in.freight());
      notes.add("Freight " + money(in.freight()).toPlainString() + " is not on the purchase order");
    }
    for (MatchLine line : in.lines()) {
      if (poLineFor(line, po).isEmpty()) {
        charge = charge.add(extended(line));
        notes.add("Line " + line.lineNo() + " matches no purchase-order line");
      }
    }
    return finding(ReasonCode.UNPLANNED_CHARGE, notes, charge);
  }

  private static Optional<MatchFinding> finding(
      ReasonCode code, List<String> notes, BigDecimal amount) {
    return notes.isEmpty()
        ? Optional.empty()
        : Optional.of(new MatchFinding(code, String.join("; ", notes), money(amount)));
  }

  private static Optional<PoLine> poLineFor(MatchLine line, PurchaseOrder po) {
    return line.poLineNo() == null ? Optional.empty() : po.line(line.poLineNo());
  }

  private static BigDecimal received(MatchInput in, MatchLine line) {
    return in.receivedByPoLine().getOrDefault(line.poLineNo(), BigDecimal.ZERO);
  }

  private static BigDecimal extended(MatchLine line) {
    return line.quantity().multiply(line.unitPrice());
  }

  private static BigDecimal money(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal quantity) {
    return quantity.stripTrailingZeros().toPlainString();
  }
}
