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

import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.invoice.InvoiceQueries;
import org.jwcarman.nessyap.erp.security.Callers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

  private final VendorMaster vendors;
  private final InvoiceQueries invoices;

  public VendorController(VendorMaster vendors, InvoiceQueries invoices) {
    this.vendors = vendors;
    this.invoices = invoices;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Vendor create(@RequestBody NewVendor vendor, Authentication caller) {
    return vendors.create(Callers.of(caller), vendor);
  }

  @GetMapping("/{id}")
  public Vendor get(@PathVariable UUID id) {
    return vendors.get(id);
  }

  @PostMapping("/{id}/bank-changes")
  @ResponseStatus(HttpStatus.CREATED)
  public BankAccount proposeBankChange(
      @PathVariable UUID id, @RequestBody BankChangeProposal proposal, Authentication caller) {
    return vendors.proposeBankChange(Callers.of(caller), id, proposal);
  }

  public record CallBack(String phone, Boolean vendorConfirmed) {}

  @PostMapping("/{id}/bank-changes/{accountId}/call-back")
  public Vendor callBack(
      @PathVariable UUID id,
      @PathVariable UUID accountId,
      @RequestBody CallBack callBack,
      Authentication caller) {
    vendors.recordCallBack(
        Callers.of(caller),
        id,
        accountId,
        callBack.phone(),
        Boolean.TRUE.equals(callBack.vendorConfirmed()));
    return vendors.get(id);
  }

  @PostMapping("/{id}/bank-changes/{accountId}/confirm")
  public Vendor confirm(
      @PathVariable UUID id, @PathVariable UUID accountId, Authentication caller) {
    vendors.confirmBankChange(Callers.of(caller), id, accountId);
    return vendors.get(id);
  }

  @GetMapping("/{id}/invoices")
  public List<Invoice> invoices(@PathVariable UUID id) {
    return invoices.byVendor(id);
  }
}
