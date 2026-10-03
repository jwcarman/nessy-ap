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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.mail.MailSent;
import org.jwcarman.nessyap.agent.mail.Mailer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The agent's mail, to the vendor only: people inside the company are asked on the workbench
 * ({@code ask_buyer}). The agent never chooses an address; each tool writes to the address of
 * record, so a changed or injected address cannot be reached at all. Sends per case are capped, and
 * every send lands on the case timeline.
 */
@Component
public class MailTools {

  static final int MAX_BODY = 4_000;

  public record Letter(
      @JsonPropertyDescription("The subject line; the case reference is added for you")
          String subject,
      @JsonPropertyDescription("The message, in plain text, at most 4000 characters")
          String body) {}

  /** Where a letter goes, or why it cannot go. */
  private sealed interface Recipient {
    record To(String address) implements Recipient {}

    record Nobody(String why) implements Recipient {}
  }

  private final Mailer mailer;
  private final ErpClient erp;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final JdbcClient jdbc;
  private final String peopleDomain;
  private final int maxPerCase;

  public MailTools(
      Mailer mailer,
      ErpClient erp,
      Cases cases,
      CaseTimeline timeline,
      JdbcClient jdbc,
      @Value("${ap.mail.people-domain}") String peopleDomain,
      @Value("${ap.mail.max-per-case}") int maxPerCase) {
    this.mailer = mailer;
    this.erp = erp;
    this.cases = cases;
    this.timeline = timeline;
    this.jdbc = jdbc;
    this.peopleDomain = peopleDomain;
    this.maxPerCase = maxPerCase;
  }

  public Tool<Letter> emailVendor() {
    return new Send(
        "email_vendor",
        "Email the vendor at its contact of record, for example to ask for a credit memo. Not"
            + " allowed while the vendor has an unverified bank-detail change.",
        "vendor",
        this::vendorOf);
  }

  private Recipient vendorOf(CaseRecord c) {
    if (erp.vendor(c.vendorId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode vendor)
        && vendor.path("contact").hasNonNull("email")) {
      return new Recipient.To(vendor.path("contact").get("email").asString());
    }
    return new Recipient.Nobody("The vendor could not be read, or has no contact email of record.");
  }

  private int sentSoFar(UUID exceptionId, String kind) {
    return jdbc.sql("select count(*) from outbound_mail where exception_id = :id and kind = :kind")
        .param("id", exceptionId)
        .param("kind", kind)
        .query(Integer.class)
        .single();
  }

  /** One kind of letter: who it goes to, the limits, and the record on the case. */
  private final class Send implements Tool<Letter> {

    private final ToolName name;
    private final String description;
    private final String kind;
    private final Function<CaseRecord, Recipient> recipient;

    Send(String name, String description, String kind, Function<CaseRecord, Recipient> recipient) {
      this.name = new ToolName(name);
      this.description = description;
      this.kind = kind;
      this.recipient = recipient;
    }

    @Override
    public Class<Letter> inputType() {
      return Letter.class;
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
    public Awaited<ToolResult> call(ToolCallRequest<Letter> request) {
      Optional<CaseRecord> kase = cases.forAgent(request.agentId());
      if (kase.isEmpty()) {
        return Awaited.ready(new ToolResult.Failure("This agent has no case to write about."));
      }
      ToolResult result = send(kase.get(), request.input());
      timeline.record(
          kase.get().exceptionId(),
          "tool",
          name.value()
              + " "
              + request.input().subject()
              + " -> "
              + (result instanceof ToolResult.Failure(String message)
                  ? "failed: " + message
                  : "ok"));
      return Awaited.ready(result);
    }

    private ToolResult send(CaseRecord c, Letter letter) {
      if (letter.subject() == null
          || letter.subject().isBlank()
          || letter.body() == null
          || letter.body().isBlank()) {
        return new ToolResult.Failure("A letter needs a subject and a body.");
      }
      if (letter.body().length() > MAX_BODY) {
        return new ToolResult.Failure(
            "The body is "
                + letter.body().length()
                + " characters; keep it under "
                + MAX_BODY
                + ".");
      }
      int sent = sentSoFar(c.exceptionId(), kind);
      if (sent >= maxPerCase) {
        return new ToolResult.Failure(
            "You have already written to the "
                + kind
                + " "
                + sent
                + " times on this case, the most allowed. Wait for a reply, or note the case.");
      }
      return switch (recipient.apply(c)) {
        case Recipient.Nobody(String why) -> new ToolResult.Failure(why);
        case Recipient.To(String address) -> deliver(c, address, letter);
      };
    }

    private ToolResult deliver(CaseRecord c, String address, Letter letter) {
      try {
        MailSent sent =
            mailer.send(c.exceptionId(), kind, address, letter.subject(), letter.body());
        timeline.record(c.exceptionId(), "mail-sent", kind + " " + address + ": " + sent.subject());
        return ToolResult.ok(
            new Block.Text(
                "Sent to the " + kind + " (" + address + "). A reply will come to this case."));
      } catch (MailException e) {
        return new ToolResult.Failure(
            "The mail server did not take the message ("
                + e.getMessage()
                + "). Try again shortly.");
      }
    }
  }
}
