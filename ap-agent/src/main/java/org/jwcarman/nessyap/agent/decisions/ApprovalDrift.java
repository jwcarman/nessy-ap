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
package org.jwcarman.nessyap.agent.decisions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.oversight.DeskMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks that the desk's proposals and Nessy's waiting approvals agree. They are two records of one
 * fact, joined by the call's idempotency key, and each can be wrong without the other:
 *
 * <ul>
 *   <li><b>Unheld:</b> a proposal is pending on the workbench, before its deadline, and no call
 *       waits on it. A person can decide it, and the agent never hears.
 *   <li><b>Unseen:</b> a call waits on a person, and the desk has no unanswered proposal for it. No
 *       person sees it, and the agent waits until its deadline.
 * </ul>
 *
 * <p>Either side is reported only after the settle time, because the desk records a proposal
 * moments before Nessy parks the call. A proposal past its deadline is a late decision, which the
 * desk handles on purpose, not drift.
 */
@Component
public class ApprovalDrift {

  private static final Logger log = LoggerFactory.getLogger(ApprovalDrift.class);

  /**
   * What disagrees.
   *
   * @param unheld the ids of pending proposals that no call waits on
   * @param unseen the keys of waiting calls that have no unanswered proposal
   */
  public record Drift(List<UUID> unheld, List<IdempotencyKey> unseen) {

    public Drift {
      unheld = List.copyOf(unheld);
      unseen = List.copyOf(unseen);
    }
  }

  private final Decisions decisions;
  private final AgentWork work;
  private final DeskMetrics metrics;
  private final Clock clock;
  private final Duration settle;

  public ApprovalDrift(
      Decisions decisions,
      AgentWork work,
      DeskMetrics metrics,
      Clock clock,
      @Value("${ap.decisions.drift-settle:PT1M}") Duration settle) {
    this.decisions = decisions;
    this.work = work;
    this.metrics = metrics;
    this.clock = clock;
    this.settle = settle;
  }

  @Scheduled(fixedDelayString = "${ap.decisions.drift-interval-ms:60000}")
  public void sweep() {
    Drift found = check(clock.instant());
    metrics.approvalDrift(found.unheld().size(), found.unseen().size());
    found.unheld().forEach(id -> log.warn("Decision {} is pending, and no call waits on it", id));
    found
        .unseen()
        .forEach(key -> log.warn("A call waits on a person with no proposal for it: {}", key));
  }

  /** What disagrees as of {@code now}. */
  public Drift check(Instant now) {
    Instant settled = now.minus(settle);
    List<PendingDecision> unanswered = decisions.agentsUnanswered();
    List<ApprovalRequest> waiting = work.waitingApprovals(AgentConfiguration.AGENT_TYPE);

    Set<UUID> waitingKeys =
        waiting.stream()
            .map(request -> request.idempotencyKey().value())
            .collect(Collectors.toSet());
    List<UUID> unheld =
        unanswered.stream()
            .filter(d -> d.status() == DecisionStatus.PENDING)
            .filter(d -> d.createdAt().isBefore(settled))
            .filter(d -> d.deadline() == null || d.deadline().isAfter(now))
            .filter(d -> !waitingKeys.contains(d.idempotencyKey()))
            .map(PendingDecision::id)
            .toList();

    Set<UUID> proposedKeys =
        unanswered.stream().map(PendingDecision::idempotencyKey).collect(Collectors.toSet());
    List<IdempotencyKey> unseen =
        waiting.stream()
            .filter(request -> request.askedAt().isBefore(settled))
            .map(ApprovalRequest::idempotencyKey)
            .filter(key -> !proposedKeys.contains(key.value()))
            .toList();

    return new Drift(unheld, unseen);
  }
}
