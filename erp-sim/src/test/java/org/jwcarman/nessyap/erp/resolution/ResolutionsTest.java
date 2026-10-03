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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.invoice.InvoiceStatus;
import org.jwcarman.nessyap.erp.matching.ExceptionStatus;
import org.jwcarman.nessyap.erp.matching.MatchExceptionRepository;
import org.jwcarman.nessyap.erp.support.InvalidRequestException;
import org.jwcarman.nessyap.erp.vendor.BankChangeProposal;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.jwcarman.nessyap.erp.vendor.VendorMaster;
import org.springframework.beans.factory.annotation.Autowired;

class ResolutionsTest extends ErpIntegrationTest {

  @Autowired Resolutions resolutions;
  @Autowired MatchExceptionRepository exceptions;
  @Autowired VendorMaster vendors;

  private Vendor acme;
  private Invoice overpriced;

  @BeforeEach
  void anInvoiceInException() {
    acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    overpriced = data().invoice(acme, "INV-1001", "PO-1", "100", "10.40");
  }

  private Invoice apply(String key, ResolutionAction action, long version, String amount) {
    return resolutions.apply(
        Actor.anonymous(),
        key,
        overpriced.id(),
        action,
        new ResolutionCommand(version, amount == null ? null : new BigDecimal(amount), "because"));
  }

  private long audited(String action) {
    return jdbc.sql("select count(*) from erp_audit where entity_id = :id and action = :action")
        .param("id", overpriced.id())
        .param("action", action)
        .query(Long.class)
        .single();
  }

  @Nested
  class Approving_a_variance {

    @Test
    void approves_the_total_and_resolves_the_exceptions() {
      Invoice approved = apply("k1", ResolutionAction.APPROVE_VARIANCE, 1, null);

      assertThat(approved.status()).isEqualTo(InvoiceStatus.APPROVED);
      assertThat(approved.approvedAmount()).isEqualByComparingTo("1040.00");
      assertThat(approved.version()).isEqualTo(2);
      assertThat(exceptions.findByInvoice(overpriced.id()))
          .isNotEmpty()
          .allMatch(e -> e.status() == ExceptionStatus.RESOLVED);
      assertThat(
              jdbc.sql("select count(*) from outbox where event_type = 'invoice.resolved'")
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(audited("approve-variance")).isEqualTo(1);
    }
  }

  @Test
  void a_hold_released_goes_back_to_exception() {
    apply("k1", ResolutionAction.HOLD, 1, null);

    Invoice released = apply("k2", ResolutionAction.RELEASE_HOLD, 2, null);

    assertThat(released.status()).isEqualTo(InvoiceStatus.EXCEPTION);
  }

  @Nested
  class Short_paying {

    @Test
    void approves_the_lesser_amount() {
      Invoice shortPaid = apply("k1", ResolutionAction.SHORT_PAY, 1, "950.00");

      assertThat(shortPaid.status()).isEqualTo(InvoiceStatus.APPROVED);
      assertThat(shortPaid.approvedAmount()).isEqualByComparingTo("950.00");
    }

    @Test
    void refuses_the_whole_total() {
      assertThatThrownBy(() -> apply("k1", ResolutionAction.SHORT_PAY, 1, "1040.00"))
          .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refuses_a_missing_amount() {
      assertThatThrownBy(() -> apply("k1", ResolutionAction.SHORT_PAY, 1, null))
          .isInstanceOf(InvalidRequestException.class);
    }
  }

  @Nested
  class Refuses {

    @Test
    void a_hold_on_a_matched_invoice() {
      Invoice matched = data().invoice(acme, "INV-2002", "PO-1", "1", "10.00");
      ResolutionCommand command = new ResolutionCommand(matched.version(), null, null);

      assertThatThrownBy(
              () ->
                  resolutions.apply(
                      Actor.anonymous(), "k1", matched.id(), ResolutionAction.HOLD, command))
          .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void a_stale_version() {
      assertThatThrownBy(() -> apply("k1", ResolutionAction.HOLD, 0, null))
          .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void a_command_with_no_version() {
      ResolutionCommand command = new ResolutionCommand(null, null, null);

      assertThatThrownBy(
              () ->
                  resolutions.apply(
                      Actor.anonymous(), "k1", overpriced.id(), ResolutionAction.HOLD, command))
          .isInstanceOf(InvalidRequestException.class);
    }
  }

  @Nested
  class When_the_vendor_has_an_unverified_bank_change {

    @BeforeEach
    void aProposedChange() {
      vendors.proposeBankChange(
          Actor.system(),
          acme.id(),
          new BankChangeProposal("998877665", "026009593", "accounts@acme-billing.example"));
    }

    @Test
    void approving_is_refused() {
      assertThatThrownBy(() -> apply("k1", ResolutionAction.APPROVE_VARIANCE, 1, null))
          .isInstanceOf(BankChangeUnverifiedException.class);
    }

    @Test
    void short_paying_is_refused() {
      assertThatThrownBy(() -> apply("k1", ResolutionAction.SHORT_PAY, 1, "500.00"))
          .isInstanceOf(BankChangeUnverifiedException.class);
    }

    @Test
    void releasing_a_hold_is_refused() {
      apply("k1", ResolutionAction.HOLD, 1, null);

      assertThatThrownBy(() -> apply("k2", ResolutionAction.RELEASE_HOLD, 2, null))
          .isInstanceOf(BankChangeUnverifiedException.class);
    }

    @Test
    void holding_and_rejecting_are_still_allowed() {
      apply("k1", ResolutionAction.HOLD, 1, null);

      assertThat(apply("k2", ResolutionAction.REJECT, 2, null).status())
          .isEqualTo(InvoiceStatus.REJECTED);
    }
  }

  @Nested
  class Idempotency_keys {

    @Test
    void a_repeated_command_is_applied_once_and_answered_the_same() {
      Invoice first = apply("k1", ResolutionAction.HOLD, 1, null);

      Invoice second = apply("k1", ResolutionAction.HOLD, 1, null);

      assertThat(second).isEqualTo(first);
      assertThat(second.version()).isEqualTo(2);
      assertThat(audited("hold")).isEqualTo(1);
    }

    @Test
    void a_key_reused_for_a_different_command_is_refused() {
      apply("k1", ResolutionAction.HOLD, 1, null);

      assertThatThrownBy(() -> apply("k1", ResolutionAction.REJECT, 2, null))
          .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void the_same_key_at_the_same_moment_is_applied_once() throws Exception {
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        List<Future<Invoice>> results =
            List.of(
                pool.submit(
                    () -> {
                      start.await();
                      return apply("k1", ResolutionAction.HOLD, 1, null);
                    }),
                pool.submit(
                    () -> {
                      start.await();
                      return apply("k1", ResolutionAction.HOLD, 1, null);
                    }));
        start.countDown();

        assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
      }
      assertThat(audited("hold")).isEqualTo(1);
    }
  }
}
