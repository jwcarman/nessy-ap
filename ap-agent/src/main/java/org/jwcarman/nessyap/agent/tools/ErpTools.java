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
package org.jwcarman.nessyap.agent.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent's read-only view of the ERP, plus a notebook line on the case. Every call lands on the
 * case timeline, and no call ever throws: ERP trouble comes back as a failure the model reads.
 */
@Component
public class ErpTools {

  public record InvoiceRef(
      @JsonPropertyDescription("The invoice id (a UUID), as given in the case") UUID invoiceId) {}

  public record PoRef(
      @JsonPropertyDescription("The purchase-order number, e.g. PO-3F9A12BC") String poNumber) {}

  public record VendorRef(@JsonPropertyDescription("The vendor id (a UUID)") UUID vendorId) {}

  public record SimilarQuery(
      @JsonPropertyDescription("The vendor id (a UUID)") UUID vendorId,
      @JsonPropertyDescription("An invoice number, written any way; matched ignoring punctuation")
          String invoiceNumber,
      @JsonPropertyDescription("Optional: also match invoices with exactly this total")
          BigDecimal total) {}

  public record Note(
      @JsonPropertyDescription("What to record on the case, for the people who work it")
          String text) {}

  private final ErpClient erp;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final JsonMapper json;

  public ErpTools(ErpClient erp, Cases cases, CaseTimeline timeline, JsonMapper json) {
    this.erp = erp;
    this.cases = cases;
    this.timeline = timeline;
    this.json = json;
  }

  public List<Tool<?>> all() {
    return List.of(
        getInvoice(),
        getPurchaseOrder(),
        getReceipts(),
        getVendor(),
        findSimilarInvoices(),
        vendorInvoiceHistory(),
        noteCase());
  }

  public Tool<InvoiceRef> getInvoice() {
    return new Read<>(
        "get_invoice",
        "Read an invoice: its lines, totals, status, version, and every match exception raised"
            + " against it.",
        InvoiceRef.class,
        in -> erp.invoice(in.invoiceId()),
        Function.identity());
  }

  public Tool<PoRef> getPurchaseOrder() {
    return new Read<>(
        "get_purchase_order",
        "Read a purchase order: the vendor, the buyer who placed it, and each line's ordered"
            + " quantity and agreed unit price.",
        PoRef.class,
        in -> erp.purchaseOrder(in.poNumber()),
        Function.identity());
  }

  public Tool<PoRef> getReceipts() {
    return new Read<>(
        "get_receipts",
        "Read every goods receipt posted against a purchase order: what arrived, per PO line,"
            + " and when.",
        PoRef.class,
        in -> erp.receipts(in.poNumber()),
        Function.identity());
  }

  public Tool<VendorRef> getVendor() {
    return new Read<>(
        "get_vendor",
        "Read a vendor: contact of record, payment terms, and every bank account it has had,"
            + " including any change still awaiting verification. Account numbers are masked.",
        VendorRef.class,
        in -> erp.vendor(in.vendorId()),
        ErpTools::maskAccounts);
  }

  public Tool<SimilarQuery> findSimilarInvoices() {
    return new Read<>(
        "find_similar_invoices",
        "Find a vendor's invoices that may be the same bill: the same number however it is"
            + " written, or (when a total is given) the same total.",
        SimilarQuery.class,
        in -> erp.similarInvoices(in.vendorId(), in.invoiceNumber(), in.total()),
        Function.identity());
  }

  public Tool<VendorRef> vendorInvoiceHistory() {
    return new Read<>(
        "get_vendor_invoice_history",
        "List a vendor's invoices, newest first, with their statuses.",
        VendorRef.class,
        in -> erp.vendorInvoices(in.vendorId()),
        Function.identity());
  }

  public Tool<Note> noteCase() {
    return new Tool<>() {
      @Override
      public Class<Note> inputType() {
        return Note.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("note_case");
      }

      @Override
      public String description() {
        return "Add a note to the case timeline, for the people who work this case.";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Note> request) {
        cases
            .forAgent(request.agentId())
            .ifPresent(c -> timeline.record(c.exceptionId(), "note", request.input().text()));
        return Awaited.ready(ToolResult.ok(new Block.Text("Noted on the case.")));
      }
    };
  }

  private static JsonNode maskAccounts(JsonNode vendor) {
    for (JsonNode account : vendor.path("bankAccounts")) {
      if (account instanceof ObjectNode editable && account.hasNonNull("accountNumber")) {
        String number = account.get("accountNumber").asString();
        String last4 = number.length() <= 4 ? number : number.substring(number.length() - 4);
        editable.put("accountNumber", "*".repeat(Math.max(0, number.length() - 4)) + last4);
      }
    }
    return vendor;
  }

  /** One read against the ERP, shown to the model as the ERP's own JSON. */
  private final class Read<I> implements Tool<I> {

    private final ToolName name;
    private final String description;
    private final Class<I> inputType;
    private final Function<I, ErpOutcome<JsonNode>> fetch;
    private final Function<JsonNode, JsonNode> shown;

    Read(
        String name,
        String description,
        Class<I> inputType,
        Function<I, ErpOutcome<JsonNode>> fetch,
        Function<JsonNode, JsonNode> shown) {
      this.name = new ToolName(name);
      this.description = description;
      this.inputType = inputType;
      this.fetch = fetch;
      this.shown = shown;
    }

    @Override
    public Class<I> inputType() {
      return inputType;
    }

    @Override
    public ToolName name() {
      return name;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<I> request) {
      ErpOutcome<JsonNode> outcome = fetch.apply(request.input());
      ToolResult result =
          switch (outcome) {
            case ErpOutcome.Ok<JsonNode>(JsonNode value) ->
                ToolResult.ok(
                    new Block.Text(
                        json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(shown.apply(value))));
            case ErpOutcome.Refused<JsonNode>(int status, String code, String detail) ->
                new ToolResult.Failure(code + ": " + detail);
            case ErpOutcome.Unavailable<JsonNode>(String reason) ->
                new ToolResult.Failure(
                    "The ERP is unavailable (" + reason + "). Try again shortly.");
          };
      cases
          .forAgent(request.agentId())
          .ifPresent(
              c ->
                  timeline.record(
                      c.exceptionId(),
                      "tool",
                      name.value()
                          + " "
                          + json.writeValueAsString(request.input())
                          + " -> "
                          + summary(result)));
      return Awaited.ready(result);
    }

    private static String summary(ToolResult result) {
      return switch (result) {
        case ToolResult.Success _ -> "ok";
        case ToolResult.Failure(String message) -> "failed: " + message;
      };
    }
  }
}
