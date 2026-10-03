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
package org.jwcarman.nessyap.erp.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.erp.audit.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

  private final InvoiceIntake intake;
  private final InvoiceQueries queries;

  public InvoiceController(InvoiceIntake intake, InvoiceQueries queries) {
    this.intake = intake;
    this.queries = queries;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InvoiceView receive(@RequestBody NewInvoice invoice) {
    return queries.get(intake.receive(Actor.anonymous(), invoice).id());
  }

  @GetMapping("/similar")
  public List<Invoice> similar(
      @RequestParam UUID vendorId,
      @RequestParam String invoiceNumber,
      @RequestParam(required = false) BigDecimal total) {
    return queries.similar(vendorId, invoiceNumber, total);
  }

  @GetMapping("/{id}")
  public InvoiceView get(@PathVariable UUID id) {
    return queries.get(id);
  }
}
