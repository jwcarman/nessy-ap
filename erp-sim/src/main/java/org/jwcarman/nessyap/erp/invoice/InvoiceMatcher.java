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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessyap.erp.matching.MatchEngine;
import org.jwcarman.nessyap.erp.matching.MatchFinding;
import org.jwcarman.nessyap.erp.matching.MatchInput;
import org.jwcarman.nessyap.erp.matching.MatchLine;
import org.jwcarman.nessyap.erp.po.GoodsReceipt;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;
import org.jwcarman.nessyap.erp.po.PurchaseOrderRepository;
import org.jwcarman.nessyap.erp.po.ReceiptLine;
import org.jwcarman.nessyap.erp.po.ReceiptRepository;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.stereotype.Component;

/** Gathers what a three-way match looks at for a stored invoice, and runs it. */
@Component
class InvoiceMatcher {

  private final InvoiceRepository invoices;
  private final PurchaseOrderRepository purchaseOrders;
  private final ReceiptRepository receipts;
  private final MatchEngine engine;

  InvoiceMatcher(
      InvoiceRepository invoices,
      PurchaseOrderRepository purchaseOrders,
      ReceiptRepository receipts,
      MatchEngine engine) {
    this.invoices = invoices;
    this.purchaseOrders = purchaseOrders;
    this.receipts = receipts;
    this.engine = engine;
  }

  List<MatchFinding> match(Invoice invoice, Vendor vendor) {
    PurchaseOrder po =
        invoice.poNumber() == null
            ? null
            : purchaseOrders.findByNumber(invoice.poNumber()).orElse(null);
    return engine.match(
        new MatchInput(
            invoice.id(),
            invoice.invoiceNumber(),
            invoice.invoiceDate(),
            vendor.id(),
            invoice.poNumber(),
            invoice.freight(),
            invoice.total(),
            invoice.lines().stream()
                .map(l -> new MatchLine(l.lineNo(), l.poLineNo(), l.quantity(), l.unitPrice()))
                .toList(),
            po,
            po == null ? Map.of() : receivedByLine(receipts.findByPo(po.id())),
            vendor.hasUnverifiedBankChange(),
            invoices.priorInvoices(vendor.id(), invoice.id())));
  }

  private static Map<Integer, BigDecimal> receivedByLine(List<GoodsReceipt> receipts) {
    Map<Integer, BigDecimal> received = new HashMap<>();
    for (GoodsReceipt receipt : receipts) {
      for (ReceiptLine line : receipt.lines()) {
        received.merge(line.poLineNo(), line.quantity(), BigDecimal::add);
      }
    }
    return received;
  }
}
