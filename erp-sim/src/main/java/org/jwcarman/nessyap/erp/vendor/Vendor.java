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
package org.jwcarman.nessyap.erp.vendor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A supplier, with every bank account it has ever had, oldest first. */
public record Vendor(
    UUID id,
    String name,
    String paymentTerms,
    Contact contact,
    Instant createdAt,
    List<BankAccount> bankAccounts) {

  public Vendor {
    bankAccounts = List.copyOf(bankAccounts);
  }

  /** True while a requested change to where this vendor is paid has not been verified. */
  public boolean hasUnverifiedBankChange() {
    return bankAccounts.stream()
        .anyMatch(account -> account.status() == BankAccountStatus.PENDING_VERIFICATION);
  }
}
