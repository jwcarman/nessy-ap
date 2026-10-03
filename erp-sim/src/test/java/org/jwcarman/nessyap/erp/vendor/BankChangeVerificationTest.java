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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.invoice.InvoiceStatus;
import org.jwcarman.nessyap.erp.resolution.NotAuthorisedException;
import org.jwcarman.nessyap.erp.resolution.ResolutionAction;
import org.jwcarman.nessyap.erp.resolution.ResolutionCommand;
import org.jwcarman.nessyap.erp.resolution.Resolutions;
import org.springframework.beans.factory.annotation.Autowired;

class BankChangeVerificationTest extends ErpIntegrationTest {

  private static final Actor MARK = new Actor("workbench", "mark");
  private static final Actor CONNIE = new Actor("workbench", "connie");

  @Autowired VendorMaster vendors;
  @Autowired Resolutions resolutions;
  @Autowired VendorRepository repository;

  private Vendor acme;
  private UUID proposed;

  @BeforeEach
  void anUnverifiedChange() {
    acme = data().vendor();
    proposed =
        vendors
            .proposeBankChange(
                Actor.system(),
                acme.id(),
                new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"))
            .id();
  }

  private String contactPhone() {
    return acme.contact().phone();
  }

  @Test
  void a_call_back_must_go_to_the_contact_of_record() {
    assertThatThrownBy(() -> vendors.recordCallBack(MARK, acme.id(), proposed, "+1-555-9999", true))
        .isInstanceOf(BankChangeVerificationException.class);
  }

  @Test
  void the_person_who_called_back_cannot_also_confirm() {
    vendors.recordCallBack(MARK, acme.id(), proposed, contactPhone(), true);

    assertThatThrownBy(() -> vendors.confirmBankChange(MARK, acme.id(), proposed))
        .isInstanceOf(BankChangeVerificationException.class);
    assertThat(vendors.get(acme.id()).hasUnverifiedBankChange()).isTrue();
  }

  @Test
  void a_change_cannot_be_confirmed_before_anyone_called() {
    assertThatThrownBy(() -> vendors.confirmBankChange(CONNIE, acme.id(), proposed))
        .isInstanceOf(BankChangeVerificationException.class);
  }

  @Test
  void a_second_person_confirming_makes_the_new_account_the_one_paid() {
    vendors.recordCallBack(MARK, acme.id(), proposed, contactPhone(), true);

    vendors.confirmBankChange(CONNIE, acme.id(), proposed);

    Vendor after = vendors.get(acme.id());
    assertThat(after.hasUnverifiedBankChange()).isFalse();
    assertThat(after.bankAccounts())
        .extracting(BankAccount::status)
        .containsExactly(BankAccountStatus.SUPERSEDED, BankAccountStatus.ACTIVE);
  }

  @Test
  void a_confirm_that_read_the_change_before_the_vendor_denied_it_cannot_undo_the_rejection() {
    vendors.recordCallBack(MARK, acme.id(), proposed, contactPhone(), false);

    // What a confirm that passed its checks a moment before the denial landed goes on to write.
    boolean activated = repository.confirm(acme.id(), proposed, "connie", Instant.now());

    assertThat(activated).isFalse();
    assertThat(vendors.get(acme.id()).bankAccounts())
        .extracting(BankAccount::status)
        .containsExactly(BankAccountStatus.ACTIVE, BankAccountStatus.REJECTED);
  }

  @Test
  void a_vendor_who_says_it_was_not_them_rejects_the_change() {
    vendors.recordCallBack(MARK, acme.id(), proposed, contactPhone(), false);

    Vendor after = vendors.get(acme.id());
    assertThat(after.hasUnverifiedBankChange()).isFalse();
    assertThat(after.bankAccounts())
        .extracting(BankAccount::status)
        .containsExactly(BankAccountStatus.ACTIVE, BankAccountStatus.REJECTED);
  }

  @Test
  void only_someone_trusted_with_vendor_changes_may_call_back() {
    Actor clara = new Actor("workbench", "clara");

    assertThatThrownBy(
            () -> vendors.recordCallBack(clara, acme.id(), proposed, contactPhone(), true))
        .isInstanceOf(NotAuthorisedException.class);
  }

  @Test
  void once_verified_a_held_invoice_can_be_released() {
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    Invoice invoice = data().invoice(acme, "INV-1", "PO-1", "100", "10.00");
    Invoice held =
        resolutions.apply(
            CONNIE,
            "k1",
            invoice.id(),
            ResolutionAction.HOLD,
            new ResolutionCommand(invoice.version(), null, "verify first"));
    vendors.recordCallBack(MARK, acme.id(), proposed, contactPhone(), true);
    vendors.confirmBankChange(CONNIE, acme.id(), proposed);

    Invoice released =
        resolutions.apply(
            CONNIE,
            "k2",
            invoice.id(),
            ResolutionAction.RELEASE_HOLD,
            new ResolutionCommand(held.version(), null, "verified"));

    assertThat(released.status()).isEqualTo(InvoiceStatus.EXCEPTION);
  }
}
