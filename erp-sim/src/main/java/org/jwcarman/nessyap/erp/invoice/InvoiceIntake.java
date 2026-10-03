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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.audit.AuditLog;
import org.jwcarman.nessyap.erp.matching.ExceptionStatus;
import org.jwcarman.nessyap.erp.matching.MatchException;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.jwcarman.nessyap.erp.matching.MatchFinding;
import org.jwcarman.nessyap.erp.outbox.Outbox;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Where invoices arrive: each is stored, matched, and any exceptions raised, in one go. */
@Service
@Transactional
public class InvoiceIntake {

  private final InvoiceRepository invoices;
  private final MatchExceptionRepository exceptions;
  private final VendorMaster vendors;
  private final InvoiceMatcher matcher;
  private final AuditLog audit;
  private final Outbox outbox;
  private final Clock clock;

  public InvoiceIntake(
      InvoiceRepository invoices,
      MatchExceptionRepository exceptions,
      VendorMaster vendors,
      InvoiceMatcher matcher,
      AuditLog audit,
      Outbox outbox,
      Clock clock) {
    this.invoices = invoices;
    this.exceptions = exceptions;
    this.vendors = vendors;
    this.matcher = matcher;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  public Invoice receive(Actor actor, NewInvoice incoming) {
    Vendor vendor = vendors.get(incoming.vendorId());
    validate(incoming);
    UUID id = Ids.next();
    Instant now = clock.instant();
    BigDecimal total = totalOf(incoming);
    Invoice received =
        new Invoice(
            id,
            vendor.id(),
            incoming.invoiceNumber(),
            incoming.poNumber(),
            incoming.invoiceDate(),
            incoming.tax(),
            incoming.freight(),
            total,
            null,
            InvoiceStatus.RECEIVED,
            0,
            now,
            incoming.lines());
    invoices.insert(received);
    audit.record(
        actor,
        "invoice",
        id,
        "received",
        incoming.invoiceNumber() + " for " + total.toPlainString());

    List<MatchFinding> findings = matcher.match(received, vendor);

    invoices.updateStatus(
        id, findings.isEmpty() ? InvoiceStatus.MATCHED : InvoiceStatus.EXCEPTION, null, 0);
    for (MatchFinding finding : findings) {
      raise(actor, id, incoming, vendor, finding, now);
    }
    return invoices.find(id).orElseThrow();
  }

  private void raise(
      Actor actor,
      UUID invoiceId,
      NewInvoice incoming,
      Vendor vendor,
      MatchFinding finding,
      Instant now) {
    MatchException exception =
        new MatchException(
            Ids.next(),
            invoiceId,
            finding.code(),
            finding.summary(),
            finding.amountAtIssue(),
            ExceptionStatus.OPEN,
            now,
            null);
    exceptions.insert(exception);
    audit.record(
        actor,
        "match_exception",
        exception.id(),
        "raised",
        finding.code() + ": " + finding.summary());
    outbox.append(
        new MatchExceptionRaised(
            Ids.next(),
            now,
            exception.id(),
            invoiceId,
            incoming.invoiceNumber(),
            vendor.id(),
            incoming.poNumber(),
            finding.code(),
            finding.summary(),
            finding.amountAtIssue()));
  }

  private static void validate(NewInvoice incoming) {
    if (incoming.invoiceNumber() == null || incoming.invoiceNumber().isBlank()) {
      throw new InvalidRequestException("An invoice needs a number");
    }
    if (incoming.invoiceDate() == null) {
      throw new InvalidRequestException("An invoice needs a date");
    }
    if (incoming.lines().isEmpty()) {
      throw new InvalidRequestException("An invoice needs at least one line");
    }
    Set<Integer> seen = new HashSet<>();
    for (InvoiceLine line : incoming.lines()) {
      if (!seen.add(line.lineNo())) {
        throw new InvalidRequestException("Line " + line.lineNo() + " appears twice");
      }
      if (line.quantity().signum() <= 0 || line.unitPrice().signum() <= 0) {
        throw new InvalidRequestException(
            "Line " + line.lineNo() + " needs a positive quantity and price");
      }
    }
  }

  private static BigDecimal totalOf(NewInvoice incoming) {
    return incoming.lines().stream()
        .map(l -> l.quantity().multiply(l.unitPrice()))
        .reduce(incoming.tax().add(incoming.freight()), BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
