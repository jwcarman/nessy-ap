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
package org.jwcarman.nessyap.erp.matching;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;

/**
 * Everything a three-way match looks at, gathered up front so matching itself touches nothing.
 *
 * @param poNumber the purchase-order number as written on the invoice; null when it cites none
 * @param purchaseOrder the order with that number; null when none exists
 * @param receivedByPoLine total quantity received so far, per purchase-order line number
 */
public record MatchInput(
    UUID invoiceId,
    String invoiceNumber,
    LocalDate invoiceDate,
    UUID vendorId,
    String poNumber,
    BigDecimal freight,
    BigDecimal total,
    List<MatchLine> lines,
    PurchaseOrder purchaseOrder,
    Map<Integer, BigDecimal> receivedByPoLine,
    boolean vendorHasUnverifiedBankChange,
    List<PriorInvoice> priorInvoices) {

  public MatchInput {
    lines = List.copyOf(lines);
    receivedByPoLine = Map.copyOf(receivedByPoLine);
    priorInvoices = List.copyOf(priorInvoices);
  }
}
