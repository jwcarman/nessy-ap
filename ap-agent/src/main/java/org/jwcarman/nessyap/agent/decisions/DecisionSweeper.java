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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes decisions that were made but not carried through: the ERP was down, or the process
 * stopped half-way. Safe to repeat, because the ERP applies a decision's command at most once.
 */
@Component
public class DecisionSweeper {

  private static final Logger log = LoggerFactory.getLogger(DecisionSweeper.class);

  private final Decisions decisions;
  private final DecisionExecutor executor;
  private final Clock clock;

  public DecisionSweeper(Decisions decisions, DecisionExecutor executor, Clock clock) {
    this.decisions = decisions;
    this.executor = executor;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${ap.decisions.sweep-interval-ms:10000}")
  public void sweep() {
    sweep(Duration.ofSeconds(30));
  }

  /** Carries through every decision decided at least {@code age} ago and still not answered. */
  public void sweep(Duration age) {
    decisions
        .decidedBefore(clock.instant().minus(age))
        .forEach(
            id -> {
              try {
                executor.decide(id, "sweeper", true, null);
              } catch (RuntimeException e) {
                log.warn("Could not carry through decision {}; will try again", id, e);
              }
            });
  }
}
