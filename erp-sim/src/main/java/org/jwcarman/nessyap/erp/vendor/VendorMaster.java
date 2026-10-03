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
package org.jwcarman.nessyap.erp.vendor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.VendorBankChangeProposed;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.audit.AuditLog;
import org.jwcarman.nessyap.erp.outbox.Outbox;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The vendor master: who the ERP buys from and where it pays them. */
@Service
@Transactional
public class VendorMaster {

  private final VendorRepository vendors;
  private final AuditLog audit;
  private final Outbox outbox;
  private final Clock clock;

  public VendorMaster(VendorRepository vendors, AuditLog audit, Outbox outbox, Clock clock) {
    this.vendors = vendors;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  public Vendor create(Actor actor, NewVendor vendor) {
    if (vendor.name() == null || vendor.name().isBlank()) {
      throw new InvalidRequestException("A vendor needs a name");
    }
    UUID id = Ids.next();
    Instant now = clock.instant();
    vendors.insert(id, vendor, now);
    vendors.insertBankAccount(
        id,
        new BankAccount(
            Ids.next(),
            vendor.accountNumber(),
            vendor.routingNumber(),
            BankAccountStatus.ACTIVE,
            now,
            null));
    audit.record(actor, "vendor", id, "created", vendor.name());
    return get(id);
  }

  @Transactional(readOnly = true)
  public Vendor get(UUID id) {
    return vendors.find(id).orElseThrow(() -> new NotFoundException("vendor", id));
  }

  public BankAccount proposeBankChange(Actor actor, UUID vendorId, BankChangeProposal proposal) {
    get(vendorId);
    Instant now = clock.instant();
    BankAccount account =
        new BankAccount(
            Ids.next(),
            proposal.accountNumber(),
            proposal.routingNumber(),
            BankAccountStatus.PENDING_VERIFICATION,
            now,
            proposal.proposedByEmail());
    vendors.insertBankAccount(vendorId, account);
    audit.record(
        actor,
        "vendor",
        vendorId,
        "bank-change-proposed",
        "New account " + account.id() + " requested by " + proposal.proposedByEmail());
    outbox.append(new VendorBankChangeProposed(Ids.next(), now, vendorId, account.id()));
    return account;
  }
}
