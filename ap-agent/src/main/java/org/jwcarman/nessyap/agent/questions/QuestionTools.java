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
package org.jwcarman.nessyap.agent.questions;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The agent asks the people inside the company on the workbench, never by mail. The question goes
 * to the buyer the ERP names on the case's purchase order: the agent never chooses the person.
 */
@Component
public class QuestionTools {

  public record Ask(
      @JsonPropertyDescription("One question, with the invoice number, at most 1000 characters")
          String question,
      @JsonPropertyDescription(
              "Up to four short answers the buyer can pick, such as \"Agreed\" and \"Not"
                  + " agreed\"; empty for an open question")
          List<String> choices) {}

  private final Questions questions;
  private final QuestionNotice notice;
  private final Cases cases;
  private final ErpClient erp;

  public QuestionTools(Questions questions, QuestionNotice notice, Cases cases, ErpClient erp) {
    this.questions = questions;
    this.notice = notice;
    this.cases = cases;
    this.erp = erp;
  }

  public Tool<Ask> askBuyer() {
    return new Tool<>() {
      @Override
      public Class<Ask> inputType() {
        return Ask.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("ask_buyer");
      }

      @Override
      public String description() {
        return "Ask the buyer who placed this case's purchase order a question on the workbench,"
            + " for example whether a price was agreed or which order an invoice belongs to. The"
            + " answer comes back to this case.";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Ask> request) {
        Optional<CaseRecord> kase = cases.forAgent(request.agentId());
        if (kase.isEmpty()) {
          return Awaited.ready(new ToolResult.Failure("This agent has no case to ask about."));
        }
        return Awaited.ready(ask(kase.get(), request.input()));
      }
    };
  }

  private ToolResult ask(CaseRecord c, Ask ask) {
    Optional<String> buyer = buyerOf(c);
    if (buyer.isEmpty()) {
      return new ToolResult.Failure(
          c.poNumber() == null
              ? "This case has no purchase order, so there is no buyer of record to ask."
              : "The purchase order " + c.poNumber() + " could not be read, or names no buyer.");
    }
    try {
      questions.ask(c.exceptionId(), buyer.get(), ask.question(), ask.choices());
    } catch (IllegalArgumentException | IllegalStateException refused) {
      return new ToolResult.Failure(refused.getMessage());
    }
    String told;
    try {
      notice.send(buyer.get(), c.invoiceNumber());
      told = "They have been told it waits.";
    } catch (MailException e) {
      told = "The notice mail failed, but the question waits on their worklist.";
    }
    return ToolResult.ok(
        new Block.Text(
            "Asked "
                + buyer.get()
                + " on the workbench. "
                + told
                + " The answer will come to this case; until then, hold."));
  }

  private Optional<String> buyerOf(CaseRecord c) {
    if (c.poNumber() != null
        && erp.purchaseOrder(c.poNumber()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode po)
        && po.hasNonNull("buyer")) {
      return Optional.of(po.get("buyer").asString());
    }
    return Optional.empty();
  }
}
