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
package org.jwcarman.nessyap.agent.workbench;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.Grounding;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.decisions.Provenance;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.questions.Question;
import org.jwcarman.nessyap.agent.questions.Questions;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The AP workbench's case page: a case with its evidence and timeline, and the quarantined mail a
 * person may read. The worklist, the decisions and the steering of the agents have their own
 * controllers under the same URLs.
 */
@Controller
@RequestMapping("/workbench")
public class WorkbenchController {

  private final Cases cases;
  private final CaseTimeline timeline;
  private final Decisions decisions;
  private final ErpClient erp;
  private final Quarantine quarantine;
  private final Questions questions;
  private final Grounding grounding;

  public WorkbenchController(
      Cases cases,
      CaseTimeline timeline,
      Decisions decisions,
      ErpClient erp,
      Questions questions,
      Grounding grounding,
      Quarantine quarantine) {
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
    this.erp = erp;
    this.quarantine = quarantine;
    this.questions = questions;
    this.grounding = grounding;
  }

  /** A pending decision as the worklist shows it: the decision and the case it belongs to. */
  public record Waiting(PendingDecision decision, CaseRecord kase) {}

  /** A question that waits for the signed-in person, with its case. */
  public record Asked(Question question, CaseRecord kase) {}

  @GetMapping("/cases/{exceptionId}")
  public String kase(@PathVariable UUID exceptionId, Authentication me, Model model) {
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    Set<String> roles = RealmRoles.of(me);
    List<PendingDecision> all = decisions.forCase(exceptionId);
    model.addAttribute("me", me.getName());
    model.addAttribute("roles", roles);
    model.addAttribute("kase", c);
    model.addAttribute("integrity", cases.integrity(exceptionId));
    model.addAttribute("exceptionId", exceptionId);
    model.addAttribute("invoice", read(erp.invoice(c.invoiceId())));
    model.addAttribute(
        "purchaseOrder", c.poNumber() == null ? null : read(erp.purchaseOrder(c.poNumber())));
    model.addAttribute("receipts", c.poNumber() == null ? null : read(erp.receipts(c.poNumber())));
    model.addAttribute("timeline", timeline.of(exceptionId));
    model.addAttribute("decisions", all);
    model.addAttribute("questions", questions.forCase(exceptionId));
    // What the approver should know: citations the agent never actually read.
    model.addAttribute(
        "ungrounded",
        all.stream().collect(Collectors.toMap(PendingDecision::id, grounding::ungrounded)));
    // What produced each proposal, for the person who decides it and for the record.
    Map<UUID, Provenance.Stamp> made = new HashMap<>();
    all.forEach(d -> decisions.provenance(d.id()).ifPresent(p -> made.put(d.id(), p)));
    model.addAttribute("provenance", made);
    model.addAttribute(
        "decidable",
        all.stream()
            .filter(d -> d.status().name().equals("PENDING"))
            .filter(d -> Deciders.mayDecide(d, me.getName(), roles))
            .toList());
    return "workbench/case";
  }

  /**
   * Quarantined mail, for a person who works cases. The quarantine decides who may read it and
   * records each read; anybody else gets a 404, which says nothing about whether it exists.
   */
  @GetMapping("/mail/{handle}")
  public String mail(@PathVariable String handle, Model model) {
    Reply reply =
        quarantine
            .forPerson(handle)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such mail"));
    model.addAttribute("reply", reply);
    return "workbench/mail";
  }

  /** The timeline alone, for the case page to refresh in place. */
  @GetMapping("/cases/{exceptionId}/timeline")
  public String timeline(@PathVariable UUID exceptionId, Model model) {
    model.addAttribute("timeline", timeline.of(exceptionId));
    model.addAttribute("exceptionId", exceptionId);
    return "workbench/case :: timeline";
  }

  private static JsonNode read(ErpOutcome outcome) {
    return outcome instanceof ErpOutcome.Ok(JsonNode value) ? value : null;
  }
}
