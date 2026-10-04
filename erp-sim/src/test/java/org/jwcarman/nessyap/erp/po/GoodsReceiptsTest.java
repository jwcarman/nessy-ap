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
package org.jwcarman.nessyap.erp.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.jwcarman.nessyap.erp.vendor.Contact;
import org.jwcarman.nessyap.erp.vendor.NewVendor;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.beans.factory.annotation.Autowired;

class GoodsReceiptsTest extends ErpIntegrationTest {

  private static final Actor SYSTEM = Actor.system();

  @Autowired VendorMaster vendors;
  @Autowired PurchaseOrders purchaseOrders;
  @Autowired GoodsReceipts receipts;

  @BeforeEach
  void anOrder() {
    Vendor vendor =
        vendors.create(
            SYSTEM,
            new NewVendor(
                "Acme Fasteners",
                "NET30",
                new Contact("Ada Acme", "+1-555-0100", "ar@acme.example"),
                "000123456",
                "021000021"));
    purchaseOrders.create(
        SYSTEM,
        new NewPurchaseOrder(
            "PO-1",
            vendor.id(),
            "bob",
            List.of(new PoLine(1, "M8 bolts", new BigDecimal("100"), new BigDecimal("10.00")))));
  }

  private static NewReceipt receipt(String po, int line, String quantity) {
    return new NewReceipt(po, List.of(new ReceiptLine(line, new BigDecimal(quantity))));
  }

  @Test
  void a_posted_receipt_is_listed_against_its_order() {
    GoodsReceipt posted = receipts.post(SYSTEM, receipt("PO-1", 1, "60"));

    assertThat(purchaseOrders.receipts("PO-1"))
        .extracting(GoodsReceipt::id)
        .containsExactly(posted.id());
    assertThat(posted.lines())
        .singleElement()
        .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("60"));
  }

  @Test
  void a_posted_receipt_tells_the_world() {
    receipts.post(SYSTEM, receipt("PO-1", 1, "60"));

    assertThat(
            jdbc.sql("select count(*) from outbox where event_type = 'receipt.posted'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void a_line_the_order_does_not_have_is_refused() {
    NewReceipt stray = receipt("PO-1", 9, "1");

    assertThatThrownBy(() -> receipts.post(SYSTEM, stray))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void a_non_positive_quantity_is_refused() {
    NewReceipt nothing = receipt("PO-1", 1, "0");

    assertThatThrownBy(() -> receipts.post(SYSTEM, nothing))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void an_unknown_order_is_not_found() {
    NewReceipt lost = receipt("PO-NOPE", 1, "1");

    assertThatThrownBy(() -> receipts.post(SYSTEM, lost)).isInstanceOf(NotFoundException.class);
  }
}
