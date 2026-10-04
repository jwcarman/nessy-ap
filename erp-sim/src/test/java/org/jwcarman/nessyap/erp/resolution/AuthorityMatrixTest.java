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
package org.jwcarman.nessyap.erp.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.beans.factory.annotation.Autowired;

/** The ERP's own say over who may do what, whatever routed the decision to them. */
class AuthorityMatrixTest extends ErpIntegrationTest {

  @Autowired Resolutions resolutions;

  static Stream<Arguments> grants() {
    return Stream.of(
        Arguments.of("clara", ResolutionAction.HOLD, "100", null, "bob", true),
        Arguments.of("clara", ResolutionAction.REQUEST_CREDIT_MEMO, "100", null, "bob", true),
        Arguments.of("clara", ResolutionAction.APPROVE_VARIANCE, "100", null, "bob", false),
        Arguments.of("bob", ResolutionAction.APPROVE_VARIANCE, "100", null, "bob", true),
        Arguments.of("bob", ResolutionAction.APPROVE_VARIANCE, "100", null, "betty", false),
        Arguments.of("bob", ResolutionAction.HOLD, "100", null, "bob", false),
        Arguments.of("mark", ResolutionAction.SHORT_PAY, "100", "950.00", "bob", true),
        Arguments.of("mark", ResolutionAction.APPROVE_VARIANCE, "1000", null, "bob", false),
        Arguments.of("mark", ResolutionAction.REJECT, "100", null, "bob", true),
        Arguments.of("connie", ResolutionAction.APPROVE_VARIANCE, "100000", null, "bob", true),
        Arguments.of("audrey", ResolutionAction.HOLD, "100", null, "bob", false));
  }

  @ParameterizedTest(name = "{0} {1} on {2} units, {4}'s PO: allowed={5}")
  @MethodSource("grants")
  void the_matrix_decides(
      String user,
      ResolutionAction action,
      String quantity,
      String amount,
      String buyer,
      boolean allowed) {
    Vendor acme = data().vendor();
    data().po(acme, "PO-1", buyer);
    data().receive("PO-1", "100");
    // 10.40 a unit: 100 units is 1,040.00, 1,000 is 10,400.00, 100,000 is 1,040,000.00.
    Invoice invoice = data().invoice(acme, "INV-1", "PO-1", quantity, "10.40");
    ResolutionCommand command =
        new ResolutionCommand(
            invoice.version(), amount == null ? null : new BigDecimal(amount), "c");
    Actor actor = new Actor("workbench", user);
    UUID invoiceId = invoice.id();

    if (allowed) {
      assertThat(resolutions.apply(actor, "k", invoiceId, action, command).version())
          .isEqualTo(invoice.version() + 1);
    } else {
      assertThatThrownBy(() -> resolutions.apply(actor, "k", invoiceId, action, command))
          .isInstanceOf(NotAuthorisedException.class);
    }
  }

  @Test
  void a_client_acting_for_nobody_may_not_decide() {
    Vendor acme = data().vendor();
    data().po(acme, "PO-1");
    data().receive("PO-1", "100");
    Invoice invoice = data().invoice(acme, "INV-1", "PO-1", "100", "10.40");
    ResolutionCommand command = new ResolutionCommand(invoice.version(), null, "c");
    Actor service = new Actor("ap-agent-service", null);
    UUID invoiceId = invoice.id();

    assertThatThrownBy(
            () -> resolutions.apply(service, "k", invoiceId, ResolutionAction.HOLD, command))
        .isInstanceOf(NotAuthorisedException.class);
  }
}
