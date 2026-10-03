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
package org.jwcarman.nessyap.erp.resolution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.invoice.InvoiceRepository;
import org.jwcarman.nessyap.erp.invoice.InvoiceStatus;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.jwcarman.nessyap.erp.support.Fingerprints;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a person's decision to an invoice: once per idempotency key, only at the version read.
 */
@Service
@Transactional
public class Resolutions {

  private final InvoiceRepository invoices;
  private final MatchExceptionRepository exceptions;
  private final VendorMaster vendors;
  private final Idempotency idempotency;
  private final ResolutionRecorder recorder;
  private final JsonMapper json;

  public Resolutions(
      InvoiceRepository invoices,
      MatchExceptionRepository exceptions,
      VendorMaster vendors,
      Idempotency idempotency,
      ResolutionRecorder recorder,
      JsonMapper json) {
    this.invoices = invoices;
    this.exceptions = exceptions;
    this.vendors = vendors;
    this.idempotency = idempotency;
    this.recorder = recorder;
    this.json = json;
  }

  public Invoice apply(
      Actor actor,
      String idempotencyKey,
      UUID invoiceId,
      ResolutionAction action,
      ResolutionCommand command) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("An Idempotency-Key is required");
    }
    if (command.expectedVersion() == null) {
      throw new InvalidRequestException("expectedVersion is required");
    }
    String fingerprint =
        Fingerprints.sha256(
            action.name() + "|" + invoiceId + "|" + json.writeValueAsString(command));
    return idempotency.execute(
        idempotencyKey,
        fingerprint,
        Invoice.class,
        () -> perform(actor, invoiceId, action, command));
  }

  private Invoice perform(
      Actor actor, UUID invoiceId, ResolutionAction action, ResolutionCommand command) {
    Invoice invoice =
        invoices.find(invoiceId).orElseThrow(() -> new NotFoundException("invoice", invoiceId));
    long expected = command.expectedVersion();
    if (invoice.version() != expected) {
      throw new StaleVersionException(invoiceId, expected);
    }
    if (!action.allowedFrom(invoice.status())) {
      throw new InvalidTransitionException(action, invoice.status());
    }
    if (action.blockedByUnverifiedBankChange()
        && vendors.get(invoice.vendorId()).hasUnverifiedBankChange()) {
      throw new BankChangeUnverifiedException(invoice.vendorId());
    }
    BigDecimal approved = approvedAmount(invoice, action, command);
    if (!invoices.updateStatus(invoiceId, action.target(), approved, expected)) {
      throw new StaleVersionException(invoiceId, expected);
    }
    Instant now = recorder.now();
    if (action.target() == InvoiceStatus.APPROVED || action.target() == InvoiceStatus.REJECTED) {
      exceptions.resolveOpenForInvoice(invoiceId, now);
    }
    recorder.record(actor, invoiceId, action, command, now);
    return invoices.find(invoiceId).orElseThrow();
  }

  private static BigDecimal approvedAmount(
      Invoice invoice, ResolutionAction action, ResolutionCommand command) {
    return switch (action) {
      case APPROVE_VARIANCE -> invoice.total();
      case SHORT_PAY -> {
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0 || amount.compareTo(invoice.total()) >= 0) {
          throw new InvalidRequestException(
              "A short-pay needs an amount above zero and below the invoice total "
                  + invoice.total().toPlainString());
        }
        yield amount;
      }
      default -> invoice.approvedAmount();
    };
  }
}
