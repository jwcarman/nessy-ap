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

import java.util.UUID;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.security.Callers;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResolutionController {

  private final Resolutions resolutions;

  public ResolutionController(Resolutions resolutions) {
    this.resolutions = resolutions;
  }

  @PostMapping("/api/invoices/{invoiceId}/{action}")
  public Invoice resolve(
      @PathVariable UUID invoiceId,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ResolutionCommand command,
      Authentication caller) {
    ResolutionAction resolution =
        ResolutionAction.fromSlug(action)
            .orElseThrow(() -> new NotFoundException("resolution action", action));
    return resolutions.apply(Callers.of(caller), idempotencyKey, invoiceId, resolution, command);
  }
}
