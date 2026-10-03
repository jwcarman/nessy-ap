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
import java.time.Instant;
import org.jwcarman.nessyap.contracts.ReceiptPosted;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.audit.AuditLog;
import org.jwcarman.nessyap.erp.outbox.Outbox;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The loading dock: what arrived against which purchase order. */
@Service
@Transactional
public class GoodsReceipts {

  private final PurchaseOrders orders;
  private final ReceiptRepository receipts;
  private final AuditLog audit;
  private final Outbox outbox;
  private final Clock clock;

  public GoodsReceipts(
      PurchaseOrders orders,
      ReceiptRepository receipts,
      AuditLog audit,
      Outbox outbox,
      Clock clock) {
    this.orders = orders;
    this.receipts = receipts;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  public GoodsReceipt post(Actor actor, NewReceipt receipt) {
    PurchaseOrder order = orders.get(receipt.poNumber());
    if (receipt.lines().isEmpty()) {
      throw new InvalidRequestException("A receipt needs at least one line");
    }
    for (ReceiptLine line : receipt.lines()) {
      if (order.line(line.poLineNo()).isEmpty()) {
        throw new InvalidRequestException(
            "Purchase order " + order.poNumber() + " has no line " + line.poLineNo());
      }
      if (line.quantity().signum() <= 0) {
        throw new InvalidRequestException("Line " + line.poLineNo() + " needs a positive quantity");
      }
    }
    Instant now = clock.instant();
    GoodsReceipt posted = new GoodsReceipt(Ids.next(), order.id(), now, receipt.lines());
    receipts.insert(posted);
    audit.record(actor, "goods_receipt", posted.id(), "received", order.poNumber());
    outbox.append(new ReceiptPosted(Ids.next(), now, posted.id(), order.poNumber()));
    return posted;
  }
}
