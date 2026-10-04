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

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.audit.AuditLog;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PurchaseOrders {

  private final PurchaseOrderRepository orders;
  private final ReceiptRepository receipts;
  private final VendorMaster vendors;
  private final AuditLog audit;
  private final Clock clock;

  public PurchaseOrders(
      PurchaseOrderRepository orders,
      ReceiptRepository receipts,
      VendorMaster vendors,
      AuditLog audit,
      Clock clock) {
    this.orders = orders;
    this.receipts = receipts;
    this.vendors = vendors;
    this.audit = audit;
    this.clock = clock;
  }

  public PurchaseOrder create(Actor actor, NewPurchaseOrder order) {
    if (order.poNumber() == null || order.poNumber().isBlank()) {
      throw new InvalidRequestException("A purchase order needs a number");
    }
    vendors.get(order.vendorId());
    if (orders.findByNumber(order.poNumber()).isPresent()) {
      throw new InvalidRequestException("Purchase order " + order.poNumber() + " already exists");
    }
    validate(order.lines());
    UUID id = Ids.next();
    orders.insert(id, order, clock.instant());
    audit.append(actor, "purchase_order", id, "created", order.poNumber());
    return load(order.poNumber());
  }

  @Transactional(readOnly = true)
  public PurchaseOrder get(String poNumber) {
    return load(poNumber);
  }

  private PurchaseOrder load(String poNumber) {
    return orders
        .findByNumber(poNumber)
        .orElseThrow(() -> new NotFoundException("purchase order", poNumber));
  }

  @Transactional(readOnly = true)
  public List<GoodsReceipt> receipts(String poNumber) {
    return receipts.findByPo(load(poNumber).id());
  }

  private static void validate(List<PoLine> lines) {
    if (lines.isEmpty()) {
      throw new InvalidRequestException("A purchase order needs at least one line");
    }
    Set<Integer> seen = new HashSet<>();
    for (PoLine line : lines) {
      if (!seen.add(line.lineNo())) {
        throw new InvalidRequestException("Line " + line.lineNo() + " appears twice");
      }
      if (line.quantity().signum() <= 0 || line.unitPrice().signum() <= 0) {
        throw new InvalidRequestException(
            "Line " + line.lineNo() + " needs a positive quantity and price");
      }
    }
  }
}
