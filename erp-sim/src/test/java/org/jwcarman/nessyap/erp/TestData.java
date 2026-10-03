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
package org.jwcarman.nessyap.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.invoice.InvoiceIntake;
import org.jwcarman.nessyap.erp.invoice.InvoiceLine;
import org.jwcarman.nessyap.erp.invoice.NewInvoice;
import org.jwcarman.nessyap.erp.po.GoodsReceipt;
import org.jwcarman.nessyap.erp.po.GoodsReceipts;
import org.jwcarman.nessyap.erp.po.NewPurchaseOrder;
import org.jwcarman.nessyap.erp.po.NewReceipt;
import org.jwcarman.nessyap.erp.po.PoLine;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;
import org.jwcarman.nessyap.erp.po.PurchaseOrders;
import org.jwcarman.nessyap.erp.po.ReceiptLine;
import org.jwcarman.nessyap.erp.vendor.Contact;
import org.jwcarman.nessyap.erp.vendor.NewVendor;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.context.ApplicationContext;

/** The usual cast: Acme, a one-line PO for 100 bolts at 10.00, receipts and invoices against it. */
public final class TestData {

  public static final LocalDate INVOICE_DATE = LocalDate.of(2026, 10, 1);

  private final VendorMaster vendors;
  private final PurchaseOrders purchaseOrders;
  private final GoodsReceipts receipts;
  private final InvoiceIntake intake;

  public TestData(ApplicationContext context) {
    this.vendors = context.getBean(VendorMaster.class);
    this.purchaseOrders = context.getBean(PurchaseOrders.class);
    this.receipts = context.getBean(GoodsReceipts.class);
    this.intake = context.getBean(InvoiceIntake.class);
  }

  public Vendor vendor() {
    return vendors.create(
        Actor.system(),
        new NewVendor(
            "Acme Fasteners",
            "NET30",
            new Contact("Ada Acme", "+1-555-0100", "ar@acme.example"),
            "000123456",
            "021000021"));
  }

  public PurchaseOrder po(Vendor vendor, String poNumber) {
    return po(vendor, poNumber, "bob");
  }

  public PurchaseOrder po(Vendor vendor, String poNumber, String buyer) {
    return purchaseOrders.create(
        Actor.system(),
        new NewPurchaseOrder(
            poNumber,
            vendor.id(),
            buyer,
            List.of(new PoLine(1, "M8 bolts", new BigDecimal("100"), new BigDecimal("10.00")))));
  }

  public GoodsReceipt receive(String poNumber, String quantity) {
    return receipts.post(
        Actor.system(),
        new NewReceipt(poNumber, List.of(new ReceiptLine(1, new BigDecimal(quantity)))));
  }

  public Invoice invoice(
      Vendor vendor, String number, String poNumber, String quantity, String unitPrice) {
    return intake.receive(
        Actor.system(),
        new NewInvoice(
            vendor.id(),
            number,
            poNumber,
            INVOICE_DATE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of(
                new InvoiceLine(
                    1, 1, "M8 bolts", new BigDecimal(quantity), new BigDecimal(unitPrice)))));
  }
}
