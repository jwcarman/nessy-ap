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
package org.jwcarman.nessyap.erp.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An invoice as it arrives. The total is computed, never taken on trust. */
public record NewInvoice(
    UUID vendorId,
    String invoiceNumber,
    String poNumber,
    LocalDate invoiceDate,
    BigDecimal tax,
    BigDecimal freight,
    List<InvoiceLine> lines) {

  public NewInvoice {
    tax = tax == null ? BigDecimal.ZERO : tax;
    freight = freight == null ? BigDecimal.ZERO : freight;
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
