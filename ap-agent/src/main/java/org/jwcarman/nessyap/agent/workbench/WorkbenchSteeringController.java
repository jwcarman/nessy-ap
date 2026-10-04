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

import static org.jwcarman.nessyap.agent.workbench.WorkbenchRedirects.MESSAGE;
import static org.jwcarman.nessyap.agent.workbench.WorkbenchRedirects.TO_WORKLIST;
import static org.jwcarman.nessyap.agent.workbench.WorkbenchRedirects.toCase;

import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.PolicyConfig;
import org.jwcarman.nessyap.agent.oversight.GuardedAgents;
import org.jwcarman.nessyap.agent.questions.Answers;
import org.jwcarman.nessyap.agent.questions.Question;
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** What people tell the agents: an answer, a note, and a controller's pause or resume. */
@Controller
@RequestMapping("/workbench")
class WorkbenchSteeringController {

  private final Cases cases;
  private final CaseTimeline timeline;
  private final GuardedAgents agent;
  private final Answers answers;
  private final ResolverDesk resolverDesk;

  WorkbenchSteeringController(
      Cases cases,
      CaseTimeline timeline,
      GuardedAgents agent,
      Answers answers,
      ResolverDesk resolverDesk) {
    this.cases = cases;
    this.timeline = timeline;
    this.agent = agent;
    this.answers = answers;
    this.resolverDesk = resolverDesk;
  }

  /** The person asked answers, signed in. Nobody else may; the answer goes to the agent. */
  @PostMapping("/questions/{questionId}/answer")
  String answer(
      @PathVariable UUID questionId,
      @RequestParam(required = false) String choice,
      @RequestParam(required = false) String comment,
      Authentication me,
      RedirectAttributes redirect) {
    Answers.Answered result = answers.answer(questionId, me.getName(), choice, comment);
    String message =
        switch (result) {
          case Answers.Answered.Told _ -> "Sent to the agent.";
          case Answers.Answered.NotYours _ ->
              throw new ResponseStatusException(
                  HttpStatus.FORBIDDEN, "This question waits for someone else");
          case Answers.Answered.AlreadyAnswered _ -> "This question already has its answer.";
          case Answers.Answered.NotAChoice _ -> "Pick one of the choices.";
          case Answers.Answered.NeedsAnAnswer _ -> "Pick a choice or write an answer.";
          case Answers.Answered.NoSuchQuestion _ ->
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question");
        };
    redirect.addFlashAttribute(MESSAGE, message);
    return result instanceof Answers.Answered.Told(Question q)
        ? toCase(q.exceptionId())
        : TO_WORKLIST;
  }

  /** A controller pauses the case agents, or resumes them. */
  @PostMapping("/oversight/agents")
  String oversee(@RequestParam boolean pause, Authentication me, RedirectAttributes redirect) {
    if (!RealmRoles.of(me).contains(Deciders.CONTROLLER)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only a controller may pause or resume the agents");
    }
    if (pause) {
      agent.pause(me.getName());
      redirect.addFlashAttribute(MESSAGE, "The agents are paused.");
    } else {
      int released = agent.resume(me.getName());
      redirect.addFlashAttribute(
          MESSAGE, "The agents are resumed; " + released + " held inputs went to them.");
    }
    return TO_WORKLIST;
  }

  @PostMapping("/cases/{exceptionId}/notes")
  String note(
      @PathVariable UUID exceptionId,
      @RequestParam String text,
      Authentication me,
      RedirectAttributes redirect) {
    if (RealmRoles.of(me).stream().noneMatch(PolicyConfig.DECIDING_ROLES::contains)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only the people who decide cases may steer the agent");
    }
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    if (!text.isBlank()) {
      timeline.append(exceptionId, "note", me.getName() + ": " + text);
      // A note is for the agent: a case the rules work becomes the agent's first.
      CaseInput.PersonNote note = new CaseInput.PersonNote(me.getName(), text);
      if (!resolverDesk.handOver(exceptionId, "person", note)) {
        agent.tell(c.agentId(), note);
      }
      redirect.addFlashAttribute(MESSAGE, "Sent to the agent.");
    }
    return toCase(exceptionId);
  }
}
