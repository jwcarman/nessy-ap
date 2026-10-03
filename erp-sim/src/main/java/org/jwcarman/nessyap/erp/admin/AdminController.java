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
package org.jwcarman.nessyap.erp.admin;

import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.erp.outbox.Outbox;
import org.jwcarman.nessyap.erp.support.ErpReset;
import org.jwcarman.nessyap.erp.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Seeding and resetting the simulator. Unsecured until identity arrives in slice 4. */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final ScenarioCatalog catalog;
  private final ErpReset reset;
  private final Outbox outbox;

  public AdminController(ScenarioCatalog catalog, ErpReset reset, Outbox outbox) {
    this.catalog = catalog;
    this.reset = reset;
    this.outbox = outbox;
  }

  /** Publishes the exception's event again, under the same id, as a broker redelivery would. */
  @PostMapping("/exceptions/{exceptionId}/redeliver")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void redeliver(@PathVariable UUID exceptionId) {
    if (!outbox.redeliverRaised(exceptionId)) {
      throw new NotFoundException("match exception", exceptionId);
    }
  }

  @GetMapping("/scenarios")
  public Set<String> scenarios() {
    return catalog.names();
  }

  @PostMapping("/scenarios/{name}")
  @ResponseStatus(HttpStatus.CREATED)
  public ScenarioResult load(@PathVariable String name) {
    return catalog.load(name);
  }

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset() {
    reset.reset();
  }
}
