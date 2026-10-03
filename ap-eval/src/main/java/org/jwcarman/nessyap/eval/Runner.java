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
package org.jwcarman.nessyap.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Seeds a scenario in the ERP, waits for the agent to finish its case, and reads what it did. */
final class Runner {

  private static final Logger log = LoggerFactory.getLogger(Runner.class);
  private static final Duration POLL = Duration.ofSeconds(2);

  private final Http http;
  private final String erpUrl;
  private final String agentUrl;
  private final Duration timeout;

  Runner(Http http, String erpUrl, String agentUrl, Duration timeout) {
    this.http = http;
    this.erpUrl = erpUrl;
    this.agentUrl = agentUrl;
    this.timeout = timeout;
  }

  RunScore run(Scenario scenario, int repetition) {
    Instant started = Instant.now();
    JsonNode seeded = http.post(erpUrl + "/admin/scenarios/" + scenario.erpScenario());
    UUID exceptionId = UUID.fromString(seeded.path("exceptionIds").get(0).asString());
    log.info("{} #{}: exception {}", scenario.name(), repetition, exceptionId);
    JsonNode lastSeen = null;
    while (Duration.between(started, Instant.now()).compareTo(timeout) < 0) {
      Optional<JsonNode> view = http.get(agentUrl + "/cases/" + exceptionId);
      if (view.isPresent()) {
        lastSeen = view.get();
        if ("RESOLVED".equals(lastSeen.path("status").asString()) && settled(lastSeen)) {
          break;
        }
      }
      sleep();
    }
    Observed observed = observe(lastSeen, Duration.between(started, Instant.now()));
    RunScore score = Scoring.score(scenario, repetition, observed);
    log.info(
        "{} #{}: {} actions={} tools={} passed={}",
        scenario.name(),
        repetition,
        observed.caseStatus(),
        observed.proposedActions(),
        observed.toolsUsed(),
        score.passed());
    return score;
  }

  /** Every proposal answered: nothing still waiting on the decider or the ERP. */
  private static boolean settled(JsonNode view) {
    for (JsonNode decision : view.path("decisions")) {
      if (!"ANSWERED".equals(decision.path("status").asString())) {
        return false;
      }
    }
    return true;
  }

  private static Observed observe(JsonNode view, Duration wall) {
    if (view == null) {
      return new Observed("NEVER_OPENED", List.of(), List.of(), wall);
    }
    List<String> actions = new ArrayList<>();
    view.path("decisions").forEach(d -> actions.add(d.path("action").asString()));
    List<String> tools = new ArrayList<>();
    for (JsonNode event : view.path("timeline")) {
      if ("tool".equals(event.path("kind").asString())) {
        String text = event.path("text").asString();
        int space = text.indexOf(' ');
        tools.add(space < 0 ? text : text.substring(0, space));
      }
    }
    return new Observed(view.path("status").asString(), actions, tools, wall);
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
