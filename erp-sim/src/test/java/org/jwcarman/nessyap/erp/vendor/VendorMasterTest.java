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

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;

class VendorMasterTest extends ErpIntegrationTest {

  @Autowired VendorMaster vendors;

  private Vendor acme() {
    return vendors.create(
        Actor.system(),
        new NewVendor(
            "Acme Fasteners",
            "NET30",
            new Contact("Ada Acme", "+1-555-0100", "ar@acme.example"),
            "000123456",
            "021000021"));
  }

  @Nested
  class Creating_a_vendor {

    @Test
    void gives_it_one_active_bank_account() {
      Vendor vendor = acme();

      assertThat(vendor.bankAccounts())
          .singleElement()
          .satisfies(
              account -> {
                assertThat(account.status()).isEqualTo(BankAccountStatus.ACTIVE);
                assertThat(account.accountNumber()).isEqualTo("000123456");
              });
      assertThat(vendor.hasUnverifiedBankChange()).isFalse();
    }

    @Test
    void reads_back_what_was_written() {
      Vendor vendor = acme();

      assertThat(vendors.get(vendor.id())).isEqualTo(vendor);
    }

    @Test
    void refuses_a_blank_name() {
      NewVendor blank =
          new NewVendor(" ", "NET30", new Contact("a", "b", "c"), "000123456", "021000021");

      assertThatThrownBy(() -> vendors.create(Actor.system(), blank))
          .isInstanceOf(InvalidRequestException.class);
    }
  }

  @Test
  void an_unknown_vendor_is_not_found() {
    UUID unknown = Ids.next();

    assertThatThrownBy(() -> vendors.get(unknown)).isInstanceOf(NotFoundException.class);
  }

  @Nested
  class Proposing_a_bank_change {

    @Test
    void leaves_the_vendor_with_an_unverified_change() {
      Vendor vendor = acme();

      BankAccount proposed =
          vendors.proposeBankChange(
              Actor.system(),
              vendor.id(),
              new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"));

      assertThat(proposed.status()).isEqualTo(BankAccountStatus.PENDING_VERIFICATION);
      assertThat(proposed.proposedByEmail()).isEqualTo("accounts@acme-billing.example");
      assertThat(vendors.get(vendor.id()).hasUnverifiedBankChange()).isTrue();
    }

    @Test
    void tells_the_world_and_records_it() {
      Vendor vendor = acme();

      vendors.proposeBankChange(
          Actor.system(),
          vendor.id(),
          new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"));

      assertThat(
              jdbc.sql(
                      "select count(*) from outbox where event_type = 'vendor.bank-change.proposed'")
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(
              jdbc.sql(
                      "select count(*) from erp_audit where entity_id = :id and action = 'bank-change-proposed'")
                  .param("id", vendor.id())
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    }
  }
}
