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

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.InvoiceResolved;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.jwcarman.nessyap.erp.audit.AuditLog;
import org.jwcarman.nessyap.erp.outbox.Outbox;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.stereotype.Component;

/** The audit row and the event a decision leaves behind. */
@Component
class ResolutionRecorder {

  private final AuditLog audit;
  private final Outbox outbox;
  private final Clock clock;

  ResolutionRecorder(AuditLog audit, Outbox outbox, Clock clock) {
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  Instant now() {
    return clock.instant();
  }

  void record(
      Actor actor,
      UUID invoiceId,
      ResolutionAction action,
      ResolutionCommand command,
      Instant now) {
    audit.record(
        actor,
        "invoice",
        invoiceId,
        action.slug(),
        command.comment() == null ? "" : command.comment());
    outbox.append(
        new InvoiceResolved(Ids.next(), now, invoiceId, action.slug(), action.target().name()));
  }
}
