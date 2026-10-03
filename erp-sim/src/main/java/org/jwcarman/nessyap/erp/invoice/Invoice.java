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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A vendor's bill as the ERP holds it.
 *
 * @param poNumber the purchase-order number as the vendor wrote it; null when it cites none
 * @param approvedAmount what will be paid once approved; null until then
 * @param version bumped by every status change; commands must name the version they read
 */
public record Invoice(
    UUID id,
    UUID vendorId,
    String invoiceNumber,
    String poNumber,
    LocalDate invoiceDate,
    BigDecimal tax,
    BigDecimal freight,
    BigDecimal total,
    BigDecimal approvedAmount,
    InvoiceStatus status,
    long version,
    Instant receivedAt,
    List<InvoiceLine> lines) {

  public Invoice {
    lines = List.copyOf(lines);
  }

  public Invoice withLines(List<InvoiceLine> newLines) {
    return new Invoice(
        id,
        vendorId,
        invoiceNumber,
        poNumber,
        invoiceDate,
        tax,
        freight,
        total,
        approvedAmount,
        status,
        version,
        receivedAt,
        newLines);
  }
}
