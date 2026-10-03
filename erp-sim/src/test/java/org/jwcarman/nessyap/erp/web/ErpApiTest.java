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
package org.jwcarman.nessyap.erp.web;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.invoice.Invoice;
import org.jwcarman.nessyap.erp.support.Ids;
import org.jwcarman.nessyap.erp.vendor.Vendor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ErpApiTest extends ErpIntegrationTest {

  private MockMvc mvc;

  @BeforeEach
  void aClient() {
    mvc = mvc();
  }

  private static String invoiceJson(Vendor vendor, String number, String unitPrice) {
    return """
        {"vendorId": "%s", "invoiceNumber": "%s", "poNumber": "PO-1",
         "invoiceDate": "2026-10-01", "tax": 0, "freight": 0,
         "lines": [{"lineNo": 1, "poLineNo": 1, "description": "M8 bolts",
                    "quantity": 100, "unitPrice": %s}]}
        """
        .formatted(vendor.id(), number, unitPrice);
  }

  @Nested
  class Vendors {

    @Test
    void a_created_vendor_reads_back() throws Exception {
      String created =
          mvc.perform(
                  post("/api/vendors")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {"name": "Acme Fasteners", "paymentTerms": "NET30",
                           "contact": {"name": "Ada Acme", "phone": "+1-555-0100",
                                       "email": "ar@acme.example"},
                           "accountNumber": "000123456", "routingNumber": "021000021"}
                          """))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String id = JsonPath.read(created, "$.id");

      mvc.perform(get("/api/vendors/{id}", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Acme Fasteners"))
          .andExpect(jsonPath("$.bankAccounts[0].status").value("ACTIVE"));
    }

    @Test
    void an_unknown_vendor_is_a_404_problem() throws Exception {
      mvc.perform(get("/api/vendors/{id}", Ids.next()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void a_bank_change_can_be_proposed() throws Exception {
      Vendor vendor = data().vendor();

      mvc.perform(
              post("/api/vendors/{id}/bank-changes", vendor.id())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"accountNumber": "998877665", "routingNumber": "026009593",
                       "proposedByEmail": "accounts@acme-billing.example"}
                      """))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    }
  }

  @Nested
  class Purchase_orders_and_receipts {

    @Test
    void an_order_reads_back_with_its_receipts() throws Exception {
      data().po(data().vendor(), "PO-1");
      data().receive("PO-1", "60");

      mvc.perform(get("/api/purchase-orders/{number}", "PO-1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.lines[0].unitPrice").value(10.00));
      mvc.perform(get("/api/purchase-orders/{number}/receipts", "PO-1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].lines[0].quantity").value(60));
    }

    @Test
    void a_receipt_for_a_line_the_order_lacks_is_a_400_problem() throws Exception {
      data().po(data().vendor(), "PO-1");

      mvc.perform(
              post("/api/receipts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"poNumber": "PO-1", "lines": [{"poLineNo": 9, "quantity": 1}]}
                      """))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }

  @Nested
  class Invoices {

    private Vendor acme;

    @BeforeEach
    void aReceivedOrder() {
      acme = data().vendor();
      data().po(acme, "PO-1");
      data().receive("PO-1", "100");
    }

    @Test
    void an_overpriced_invoice_comes_back_with_its_exception() throws Exception {
      mvc.perform(
              post("/api/invoices")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invoiceJson(acme, "INV-1001", "10.40")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.invoice.status").value("EXCEPTION"))
          .andExpect(jsonPath("$.exceptions[0].reasonCode").value("PRICE_VARIANCE"));
    }

    @Test
    void reads_back_by_id() throws Exception {
      Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.00");

      mvc.perform(get("/api/invoices/{id}", invoice.id()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.invoice.status").value("MATCHED"))
          .andExpect(jsonPath("$.exceptions").isEmpty());
    }

    @Test
    void similar_finds_the_same_number_written_differently() throws Exception {
      Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.00");

      mvc.perform(
              get("/api/invoices/similar")
                  .param("vendorId", acme.id().toString())
                  .param("invoiceNumber", "inv 1001"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[*].id").value(hasItem(invoice.id().toString())));
    }

    @Test
    void a_vendors_invoices_are_listed() throws Exception {
      Invoice invoice = data().invoice(acme, "INV-1001", "PO-1", "100", "10.00");

      mvc.perform(get("/api/vendors/{id}/invoices", acme.id()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(invoice.id().toString()));
    }
  }

  @Nested
  class Match_exceptions {

    @Test
    void the_list_defaults_to_open_and_each_reads_back() throws Exception {
      Vendor acme = data().vendor();
      data().po(acme, "PO-1");
      data().receive("PO-1", "100");
      data().invoice(acme, "INV-1001", "PO-1", "100", "10.40");

      String list =
          mvc.perform(get("/api/match-exceptions"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$[0].status").value("OPEN"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      String id = JsonPath.read(list, "$[0].id");

      mvc.perform(get("/api/match-exceptions/{id}", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reasonCode").value("PRICE_VARIANCE"));
    }
  }
}
