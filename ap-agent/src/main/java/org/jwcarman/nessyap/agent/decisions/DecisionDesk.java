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
package org.jwcarman.nessyap.agent.decisions;

import java.time.Clock;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where proposals wait for a decider. It never answers on the spot: it writes the proposal down,
 * with the only address it can be answered at, and frees the agent.
 */
@Component
public class DecisionDesk implements Approver {

  private final Decisions decisions;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final JsonMapper json;
  private final Clock clock;

  public DecisionDesk(
      Decisions decisions, Cases cases, CaseTimeline timeline, JsonMapper json, Clock clock) {
    this.decisions = decisions;
    this.cases = cases;
    this.timeline = timeline;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    Optional<CaseRecord> found = cases.forAgent(request.agentId());
    if (found.isEmpty()) {
      return Awaited.ready(ApprovalResult.denied("This agent has no case to resolve."));
    }
    ProposeResolution proposal;
    try {
      proposal = json.readValue(request.arguments(), ProposeResolution.class);
    } catch (JacksonException e) {
      return Awaited.ready(
          ApprovalResult.denied("The proposal could not be read: " + e.getOriginalMessage()));
    }
    CaseRecord c = found.get();
    decisions.insert(
        new PendingDecision(
            Ids.next(),
            request.agentId(),
            request.callKey(),
            request.replyToken(),
            c.exceptionId(),
            c.invoiceId(),
            proposal.action(),
            proposal.amount(),
            proposal.rationale() == null ? "" : proposal.rationale(),
            proposal.evidence(),
            request.deadline(),
            DecisionStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            null,
            clock.instant()));
    cases.setStatus(c.exceptionId(), CaseStatus.AWAITING_DECISION);
    timeline.record(c.exceptionId(), "proposal", request.action());
    return Awaited.deferred();
  }
}
