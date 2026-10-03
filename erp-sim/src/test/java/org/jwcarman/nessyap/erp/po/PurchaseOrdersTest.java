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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.jwcarman.nessyap.erp.vendor.Contact;
import org.jwcarman.nessyap.erp.vendor.NewVendor;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.beans.factory.annotation.Autowired;

class PurchaseOrdersTest extends ErpIntegrationTest {

  @Autowired VendorMaster vendors;
  @Autowired PurchaseOrders purchaseOrders;

  private Vendor vendor;

  @BeforeEach
  void aVendor() {
    vendor =
        vendors.create(
            Actor.system(),
            new NewVendor(
                "Acme Fasteners",
                "NET30",
                new Contact("Ada Acme", "+1-555-0100", "ar@acme.example"),
                "000123456",
                "021000021"));
  }

  private NewPurchaseOrder order(String number, PoLine... lines) {
    return new NewPurchaseOrder(number, vendor.id(), "bob", List.of(lines));
  }

  private static PoLine line(int no, String quantity, String price) {
    return new PoLine(no, "M8 bolts", new BigDecimal(quantity), new BigDecimal(price));
  }

  @Test
  void a_created_order_reads_back_by_number() {
    purchaseOrders.create(Actor.system(), order("PO-1", line(1, "100", "10.00")));

    PurchaseOrder read = purchaseOrders.get("PO-1");

    assertThat(read.vendorId()).isEqualTo(vendor.id());
    assertThat(read.buyer()).isEqualTo("bob");
    assertThat(read.lines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.quantity()).isEqualByComparingTo("100");
              assertThat(l.unitPrice()).isEqualByComparingTo("10.00");
            });
    assertThat(read.line(1)).isPresent();
    assertThat(read.line(2)).isEmpty();
  }

  @Test
  void an_unknown_number_is_not_found() {
    assertThatThrownBy(() -> purchaseOrders.get("PO-NOPE")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void an_order_for_an_unknown_vendor_is_not_found() {
    NewPurchaseOrder orphan =
        new NewPurchaseOrder("PO-2", Ids.next(), "bob", List.of(line(1, "1", "1.00")));

    assertThatThrownBy(() -> purchaseOrders.create(Actor.system(), orphan))
        .isInstanceOf(NotFoundException.class);
  }

  @Nested
  class Refuses {

    @Test
    void a_number_already_in_use() {
      purchaseOrders.create(Actor.system(), order("PO-1", line(1, "100", "10.00")));
      NewPurchaseOrder again = order("PO-1", line(1, "5", "1.00"));

      assertThatThrownBy(() -> purchaseOrders.create(Actor.system(), again))
          .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void an_order_with_no_lines() {
      NewPurchaseOrder empty = order("PO-1");

      assertThatThrownBy(() -> purchaseOrders.create(Actor.system(), empty))
          .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void duplicate_line_numbers() {
      NewPurchaseOrder twice = order("PO-1", line(1, "1", "1.00"), line(1, "2", "2.00"));

      assertThatThrownBy(() -> purchaseOrders.create(Actor.system(), twice))
          .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void a_zero_price() {
      NewPurchaseOrder free = order("PO-1", line(1, "1", "0.00"));

      assertThatThrownBy(() -> purchaseOrders.create(Actor.system(), free))
          .isInstanceOf(InvalidRequestException.class);
    }
  }
}
