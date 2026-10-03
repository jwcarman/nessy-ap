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
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.erp.matching.ExceptionStatus;
import org.jwcarman.nessyap.erp.matching.InvoiceNumbers;
import org.jwcarman.nessyap.erp.matching.MatchException;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvoiceQueries {

  private final InvoiceRepository invoices;
  private final MatchExceptionRepository exceptions;

  public InvoiceQueries(InvoiceRepository invoices, MatchExceptionRepository exceptions) {
    this.invoices = invoices;
    this.exceptions = exceptions;
  }

  public InvoiceView get(UUID id) {
    Invoice invoice = invoices.find(id).orElseThrow(() -> new NotFoundException("invoice", id));
    return new InvoiceView(invoice, exceptions.findByInvoice(id));
  }

  public List<Invoice> byVendor(UUID vendorId) {
    return invoices.findByVendor(vendorId);
  }

  /**
   * A vendor's invoices that might be the same bill: the same number however it is written, or,
   * when a total is given, the same total.
   */
  public List<Invoice> similar(UUID vendorId, String invoiceNumber, BigDecimal total) {
    String number = InvoiceNumbers.normalize(invoiceNumber);
    return invoices.findByVendor(vendorId).stream()
        .filter(
            invoice ->
                InvoiceNumbers.normalize(invoice.invoiceNumber()).equals(number)
                    || (total != null && invoice.total().compareTo(total) == 0))
        .toList();
  }

  public MatchException exception(UUID id) {
    return exceptions.find(id).orElseThrow(() -> new NotFoundException("match exception", id));
  }

  public List<MatchException> exceptions(ExceptionStatus status) {
    return exceptions.findByStatus(status);
  }
}
