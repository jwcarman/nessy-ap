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
package org.jwcarman.nessyap.agent.resolver;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads the facts the rules need for a case, without a model: from the ERP, from what the rules
 * learned outside it (a vendor's reply, a decline), and from the case's declined decisions.
 *
 * <p>A fact that cannot be read is left out, so the rules see it as unknown. Each ERP read is tried
 * up to three times, because the ERP's reads can fail for a moment.
 */
@Component
public class CaseSlots {

  private static final int TRIES = 3;
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final ErpClient erp;
  private final Cases cases;
  private final Decisions decisions;

  public CaseSlots(ErpClient erp, Cases cases, Decisions decisions) {
    this.erp = erp;
    this.cases = cases;
    this.decisions = decisions;
  }

  /**
   * What the rules know about a case.
   *
   * @param slots the known facts, by the names the decision tables use
   * @param evidence the ids of every record read, for a proposal's evidence
   * @param total the invoice total, or null when the invoice could not be read
   * @param atPoPrice what the billed lines cost at the PO's prices, or null
   * @param withoutCharges the total without freight and lines the PO does not have, or null
   * @param vendorEmail the vendor's contact of record, or null
   */
  public record Read(
      Map<String, Object> slots,
      List<String> evidence,
      BigDecimal total,
      BigDecimal atPoPrice,
      BigDecimal withoutCharges,
      String vendorEmail) {}

  public Read read(CaseRecord c) {
    Map<String, Object> slots = new HashMap<>();
    Set<String> evidence = new LinkedHashSet<>();
    slots.put("reasonCode", c.reasonCode().name());

    Optional<JsonNode> view = tried(() -> erp.invoice(c.invoiceId()));
    Optional<JsonNode> po =
        c.poNumber() == null ? Optional.empty() : tried(() -> erp.purchaseOrder(c.poNumber()));
    JsonNode invoice = view.map(v -> v.path("invoice")).orElse(null);
    BigDecimal total = null;
    BigDecimal atPoPrice = null;
    BigDecimal withoutCharges = null;
    if (invoice != null) {
      evidence.add(c.invoiceId().toString());
      total = decimal(invoice.path("total"));
      BigDecimal freight = decimal(invoice.path("freight"));
      withoutCharges = total == null ? null : total.subtract(freight == null ? ZERO : freight);
    }
    if (invoice != null && po.isPresent()) {
      evidence.add(c.poNumber());
      Lines lines = lines(invoice, po.get());
      if (lines.variancePercent() != null) {
        slots.put("variancePercent", lines.variancePercent());
      }
      if (lines.billedItem() != null) {
        slots.put("billedItem", lines.billedItem());
      }
      atPoPrice = lines.atPoPrice();
      withoutCharges = withoutCharges == null ? null : withoutCharges.subtract(lines.offPo());
    }
    readReceipts(c, slots, evidence);
    String vendorEmail = readVendor(c, evidence);
    readOriginal(c, invoice, slots, evidence);
    cases.slots(c.exceptionId()).forEach(slots::put);
    declined(c).ifPresent(action -> slots.put("declinedAction", action));
    return new Read(slots, List.copyOf(evidence), total, atPoPrice, withoutCharges, vendorEmail);
  }

  /**
   * What the billed lines say against their PO lines.
   *
   * @param variancePercent the worst line's price over its PO price, or null when no line could be
   *     compared
   * @param atPoPrice what the compared lines cost at PO prices, or null when no line could be
   */
  private record Lines(
      BigDecimal variancePercent, BigDecimal atPoPrice, BigDecimal offPo, String billedItem) {}

  private static Lines lines(JsonNode invoice, JsonNode po) {
    Map<Integer, BigDecimal> poPrices = new HashMap<>();
    for (JsonNode line : po.path("lines")) {
      BigDecimal price = decimal(line.path("unitPrice"));
      if (price != null) {
        poPrices.put(line.path("lineNo").asInt(), price);
      }
    }
    BigDecimal worst = null;
    String billedItem = null;
    BigDecimal atPoPrice = null;
    BigDecimal offPo = ZERO;
    for (JsonNode line : invoice.path("lines")) {
      if (line.path("itemCode").isString() && billedItem == null) {
        billedItem = line.path("itemCode").asString();
      }
      BigDecimal quantity = decimal(line.path("quantity"));
      BigDecimal billed = decimal(line.path("unitPrice"));
      if (quantity == null || billed == null) {
        // A line the desk cannot price makes every amount from these lines untrustworthy.
        return new Lines(null, null, ZERO, billedItem);
      }
      BigDecimal poPrice =
          line.hasNonNull("poLineNo") ? poPrices.get(line.path("poLineNo").asInt()) : null;
      if (poPrice == null || poPrice.signum() == 0) {
        offPo = offPo.add(billed.multiply(quantity));
        continue;
      }
      atPoPrice = (atPoPrice == null ? ZERO : atPoPrice).add(poPrice.multiply(quantity));
      BigDecimal percent =
          billed.subtract(poPrice).multiply(HUNDRED).divide(poPrice, 2, RoundingMode.HALF_UP);
      worst = worst == null ? percent : worst.max(percent);
    }
    return new Lines(worst, atPoPrice, offPo, billedItem);
  }

  private void readReceipts(CaseRecord c, Map<String, Object> slots, Set<String> evidence) {
    if (c.poNumber() == null) {
      return;
    }
    tried(() -> erp.receipts(c.poNumber()))
        .ifPresent(
            receipts -> {
              receipts.forEach(
                  r -> {
                    if (r.path("id").isString()) {
                      evidence.add(r.path("id").asString());
                    }
                  });
              // Two invoices billed alike are two shipments when two deliveries arrived.
              slots.put("receiptsCoverBoth", receipts.size() >= 2);
            });
  }

  private String readVendor(CaseRecord c, Set<String> evidence) {
    Optional<JsonNode> vendor = tried(() -> erp.vendor(c.vendorId()));
    if (vendor.isEmpty()) {
      return null;
    }
    evidence.add(c.vendorId().toString());
    for (JsonNode account : vendor.get().path("bankAccounts")) {
      if ("PENDING_VERIFICATION".equals(account.path("status").asString())
          && account.hasNonNull("id")) {
        evidence.add(account.get("id").asString());
      }
    }
    JsonNode email = vendor.get().path("contact").path("email");
    return email.isString() ? email.asString() : null;
  }

  /** For a repeat: the earlier invoice with the same number, if the ERP has one. */
  private void readOriginal(
      CaseRecord c, JsonNode invoice, Map<String, Object> slots, Set<String> evidence) {
    if (invoice == null || !"DUPLICATE".equals(c.reasonCode().name())) {
      return;
    }
    tried(() -> erp.similarInvoices(c.vendorId(), c.invoiceNumber(), null))
        .ifPresent(
            similar -> {
              List<String> originals = new ArrayList<>();
              for (JsonNode other : similar) {
                String id = other.path("id").asString();
                if (!id.equals(c.invoiceId().toString())) {
                  originals.add(id);
                }
              }
              evidence.addAll(originals);
              slots.put("originalFound", !originals.isEmpty());
            });
  }

  /** The action of the case's latest declined decision, if a decider declined one. */
  private Optional<String> declined(CaseRecord c) {
    List<PendingDecision> all = decisions.forCase(c.exceptionId());
    for (int i = all.size() - 1; i >= 0; i--) {
      PendingDecision d = all.get(i);
      if (Boolean.FALSE.equals(d.approved())) {
        return Optional.of(d.action());
      }
    }
    return Optional.empty();
  }

  private static BigDecimal decimal(JsonNode node) {
    return node.isNumber() ? node.decimalValue() : null;
  }

  private static Optional<JsonNode> tried(Supplier<ErpOutcome<JsonNode>> read) {
    for (int i = 0; i < TRIES; i++) {
      if (read.get() instanceof ErpOutcome.Ok<JsonNode>(JsonNode value)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
