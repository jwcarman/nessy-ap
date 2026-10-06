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
package org.jwcarman.nessyap.agent.oversight;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * The desk's counts for watching drift, under {@code ap.} in Micrometer and the actuator: who
 * proposes what, who settles cases, why the rules stop, what Occlude refuses, what people's
 * oversight holds, and whether the desk's proposals and Nessy's waiting calls agree. "Controls and
 * governance" suggests an alert for each.
 */
@Component
public class DeskMetrics {

  private final MeterRegistry registry;
  private final AtomicInteger unheld = new AtomicInteger();
  private final AtomicInteger unseen = new AtomicInteger();

  public DeskMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("ap.approvals.drift", unheld, AtomicInteger::get)
        .tag("side", "unheld")
        .description("Pending proposals that no call waits on, at the last check")
        .register(registry);
    Gauge.builder("ap.approvals.drift", unseen, AtomicInteger::get)
        .tag("side", "unseen")
        .description("Calls that wait on a person with no proposal, at the last check")
        .register(registry);
  }

  /** {@code ap.agents.paused}: 1 while the case agents are paused, read at each scrape. */
  @Bean
  static MeterBinder agentsPaused(Switches switches) {
    return registry ->
        Gauge.builder("ap.agents.paused", switches, s -> s.on(Switches.AGENTS_PAUSED) ? 1 : 0)
            .description("1 while the case agents are paused")
            .register(registry);
  }

  /** A proposal reached a person. */
  public void proposed(String proposer, String action) {
    registry.counter("ap.proposals", "proposer", proposer, "action", action).increment();
  }

  /** A decision was applied, and the case resolved or went on hold. */
  public void settled(String by, String status) {
    registry.counter("ap.cases.settled", "by", by, "status", status).increment();
  }

  /** The rules gave a case to its agent. */
  public void escalated(String why) {
    registry.counter("ap.rules.escalated", "why", why).increment();
  }

  /** Occlude refused an operation at a gate. */
  public void refused(String reason) {
    registry.counter("ap.occlude.refusals", "reason", reason).increment();
  }

  /** What the last drift check found between the desk's proposals and Nessy's waiting calls. */
  public void approvalDrift(int unheldNow, int unseenNow) {
    unheld.set(unheldNow);
    unseen.set(unseenNow);
  }

  /** An input for an agent was held instead of told. */
  public void held(String reason) {
    registry.counter("ap.agents.held", "reason", reason).increment();
  }
}
