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
import java.util.Optional;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.po.PurchaseOrder;
import org.jwcarman.nessyap.erp.po.PurchaseOrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ERP's own say over who may decide what. Whatever routed a decision to a person, the ERP
 * checks the person: the action, the amount it authorises, and for a buyer, that the purchase order
 * is theirs. A client acting for nobody may decide nothing.
 */
@Component
public class AuthorityMatrix {

  private record Grant(BigDecimal maxAmount, boolean ownPoOnly) {}

  private final JdbcClient jdbc;
  private final PurchaseOrderRepository purchaseOrders;
  private final AuthorityMode mode;

  public AuthorityMatrix(
      JdbcClient jdbc, PurchaseOrderRepository purchaseOrders, AuthorityMode mode) {
    this.jdbc = jdbc;
    this.purchaseOrders = purchaseOrders;
    this.mode = mode;
  }

  /**
   * @param amount what the command authorises paying: a short-pay's amount, otherwise the total
   */
  public void check(Actor actor, ResolutionAction action, Invoice invoice, BigDecimal amount) {
    if (actor.user() == null) {
      throw new NotAuthorisedException(
          "A person must decide: " + actor.client() + " acts for nobody");
    }
    if (mode.trustsIntegrationUser()) {
      // Recorded, not checked: the weakness trust mode exists to show.
      return;
    }
    Grant grant =
        grantOf(actor.user(), action)
            .orElseThrow(
                () ->
                    new NotAuthorisedException(
                        actor.user() + " may not " + action.slug() + " an invoice"));
    if (grant.maxAmount() != null && amount.compareTo(grant.maxAmount()) > 0) {
      throw new NotAuthorisedException(
          actor.user()
              + " may "
              + action.slug()
              + " up to "
              + grant.maxAmount().toPlainString()
              + ", not "
              + amount.toPlainString());
    }
    if (grant.ownPoOnly()) {
      String buyer =
          Optional.ofNullable(invoice.poNumber())
              .flatMap(purchaseOrders::findByNumber)
              .map(PurchaseOrder::buyer)
              .orElse(null);
      if (!actor.user().equals(buyer)) {
        throw new NotAuthorisedException(
            actor.user() + " may " + action.slug() + " only on purchase orders they placed");
      }
    }
  }

  /** For actions on no invoice, such as verifying a vendor's bank change: the grant alone. */
  public void require(Actor actor, String action) {
    if (actor.user() == null || grantOf(actor.user(), action).isEmpty()) {
      throw new NotAuthorisedException(
          (actor.user() == null ? actor.client() : actor.user()) + " may not " + action);
    }
  }

  private Optional<Grant> grantOf(String username, ResolutionAction action) {
    return grantOf(username, action.slug());
  }

  private Optional<Grant> grantOf(String username, String action) {
    return jdbc.sql(
            "select max_amount, own_po_only from authority_grant where username = :u and action = :a")
        .param("u", username)
        .param("a", action)
        .query((rs, row) -> new Grant(rs.getBigDecimal("max_amount"), rs.getBoolean("own_po_only")))
        .optional();
  }
}
