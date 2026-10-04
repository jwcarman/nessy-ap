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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.mail.UnmatchedMail;
import org.jwcarman.nessyap.agent.oversight.GuardedAgents;
import org.jwcarman.nessyap.agent.questions.Questions;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The workbench's front page: what waits for the signed-in person, and the recent cases. */
@Controller
@RequestMapping("/workbench")
class WorklistController {

  private final Cases cases;
  private final Decisions decisions;
  private final GuardedAgents agent;
  private final UnmatchedMail unmatched;
  private final Questions questions;

  WorklistController(
      Cases cases,
      Decisions decisions,
      GuardedAgents agent,
      UnmatchedMail unmatched,
      Questions questions) {
    this.cases = cases;
    this.decisions = decisions;
    this.agent = agent;
    this.unmatched = unmatched;
    this.questions = questions;
  }

  @GetMapping
  String worklist(Authentication me, Model model) {
    Set<String> roles = RealmRoles.of(me);
    List<PendingDecision> mine =
        roles.contains(Deciders.CONTROLLER)
            ? decisions.allPending()
            : decisions.pendingFor(roles, me.getName());
    model.addAttribute("me", me.getName());
    model.addAttribute("roles", roles);
    model.addAttribute("agentsPaused", agent.arePaused());
    model.addAttribute(
        "waiting",
        mine.stream()
            .map(d -> cases.find(d.exceptionId()).map(c -> new WorkbenchController.Waiting(d, c)))
            .flatMap(Optional::stream)
            .toList());
    model.addAttribute(
        "asked",
        questions.waitingFor(me.getName()).stream()
            .map(q -> cases.find(q.exceptionId()).map(c -> new WorkbenchController.Asked(q, c)))
            .flatMap(Optional::stream)
            .toList());
    model.addAttribute("cases", cases.recent(50));
    // Sorting out mail no case claimed is a manager's job: it may be a new dispute or a fraud.
    model.addAttribute(
        "unmatched",
        roles.contains(Deciders.AP_MANAGER) || roles.contains(Deciders.CONTROLLER)
            ? unmatched.recent(20)
            : List.of());
    return "workbench/worklist";
  }
}
