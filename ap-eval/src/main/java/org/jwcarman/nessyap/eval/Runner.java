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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Seeds a scenario in the ERP, plays the people the routing policy names, waits for the agent to
 * resolve its case, and reads what happened.
 */
final class Runner {

  private static final Logger log = LoggerFactory.getLogger(Runner.class);
  private static final Duration POLL = Duration.ofSeconds(2);

  /** Who plays each role. The buyer of every seeded PO is bob. */
  static final Map<String, String> PEOPLE =
      Map.of("ap-clerk", "clara", "buyer", "bob", "ap-manager", "mark", "controller", "connie");

  private final Http http;
  private final Keycloak keycloak;
  private final String erpUrl;
  private final String agentUrl;
  private final Duration timeout;
  private final TokenMeter meter;

  Runner(Http http, Keycloak keycloak, String erpUrl, String agentUrl, Duration timeout) {
    this.meter = new TokenMeter(http, agentUrl);
    this.http = http;
    this.keycloak = keycloak;
    this.erpUrl = erpUrl;
    this.agentUrl = agentUrl;
    this.timeout = timeout;
  }

  RunScore run(Scenario scenario, int repetition) {
    Instant started = Instant.now();
    long spentBefore = meter.total();
    JsonNode seeded = http.post(erpUrl + "/admin/scenarios/" + scenario.erpScenario());
    UUID exceptionId = UUID.fromString(seeded.path("exceptionIds").get(0).asString());
    log.info("{} #{}: exception {}", scenario.name(), repetition, exceptionId);
    JsonNode lastSeen = null;
    while (Duration.between(started, Instant.now()).compareTo(timeout) < 0) {
      Optional<JsonNode> view = http.get(agentUrl + "/cases/" + exceptionId);
      if (view.isPresent()) {
        lastSeen = view.get();
        decidePending(lastSeen);
        if ("RESOLVED".equals(lastSeen.path("status").asString()) && settled(lastSeen)) {
          break;
        }
      }
      sleep();
    }
    long spentAfter = meter.total();
    int tokens = spentBefore < 0 || spentAfter < 0 ? -1 : (int) (spentAfter - spentBefore);
    Observed observed = observe(lastSeen, tokens, Duration.between(started, Instant.now()));
    RunScore score = Scoring.score(scenario, repetition, observed);
    log.info(
        "{} #{}: {} actions={} routed={} tools={} tokens={} passed={}",
        scenario.name(),
        repetition,
        observed.caseStatus(),
        observed.proposedActions(),
        observed.routedTo(),
        observed.toolsUsed(),
        observed.tokens(),
        score.passed());
    return score;
  }

  /** Each person approves what the policy sent them: the scenarios test the agent, not people. */
  private void decidePending(JsonNode view) {
    for (JsonNode decision : view.path("decisions")) {
      if (!"PENDING".equals(decision.path("status").asString())) {
        continue;
      }
      String role = decision.path("requiredRole").asString();
      String person =
          decision.hasNonNull("requiredUser")
              ? decision.path("requiredUser").asString()
              : PEOPLE.get(role);
      if (person == null) {
        log.warn("No one plays {}; leaving decision {} pending", role, decision.path("id"));
        continue;
      }
      JsonNode answer =
          http.postJson(
              agentUrl + "/api/decisions/" + decision.path("id").asString(),
              keycloak.tokenFor(person),
              Map.of("approve", true, "comment", "approved by the evaluation as " + person));
      log.info(
          "  {} ({}) decided {}: {}",
          person,
          role,
          decision.path("action").asString(),
          answer.path("result").asString());
    }
  }

  private static boolean settled(JsonNode view) {
    for (JsonNode decision : view.path("decisions")) {
      if (!"ANSWERED".equals(decision.path("status").asString())) {
        return false;
      }
    }
    return true;
  }

  private static Observed observe(JsonNode view, int tokens, Duration wall) {
    if (view == null) {
      return new Observed("NEVER_OPENED", List.of(), List.of(), List.of(), tokens, wall);
    }
    List<String> actions = new ArrayList<>();
    List<String> routes = new ArrayList<>();
    for (JsonNode d : view.path("decisions")) {
      actions.add(d.path("action").asString());
      routes.add(d.path("requiredRole").asString());
    }
    List<String> tools = new ArrayList<>();
    for (JsonNode event : view.path("timeline")) {
      if ("tool".equals(event.path("kind").asString())) {
        String text = event.path("text").asString();
        int space = text.indexOf(' ');
        tools.add(space < 0 ? text : text.substring(0, space));
      }
    }
    return new Observed(view.path("status").asString(), actions, tools, routes, tokens, wall);
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
