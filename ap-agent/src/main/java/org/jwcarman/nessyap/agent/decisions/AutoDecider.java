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

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Approves every proposal, as anyone, with no role check: a stand-in for people when a stack runs
 * without them (off by default; {@code ap.decisions.auto=true}). It decides on its own thread,
 * after the proposal has committed, exactly as a person would.
 */
@Component
@ConditionalOnProperty(name = "ap.decisions.auto", havingValue = "true")
public class AutoDecider {

  private static final Logger log = LoggerFactory.getLogger(AutoDecider.class);

  private final Decisions decisions;
  private final DecisionExecutor executor;

  public AutoDecider(Decisions decisions, DecisionExecutor executor) {
    this.decisions = decisions;
    this.executor = executor;
  }

  @Scheduled(fixedDelayString = "${ap.decisions.auto-interval-ms:1000}")
  public void decidePending() {
    for (UUID id : decisions.pending()) {
      try {
        executor.decide(id, "auto-decider", true, "approved automatically");
      } catch (RuntimeException e) {
        log.warn("Could not decide {}; will try again", id, e);
      }
    }
  }
}
