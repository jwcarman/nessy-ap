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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  /** Who the evaluation reads cases as: the controller sees every case. */
  private static final String OBSERVER = "connie";

  private final Http http;
  private final Keycloak keycloak;
  private final String erpUrl;
  private final String agentUrl;
  private static final Duration REDELIVER_AFTER = Duration.ofSeconds(3);

  private final Duration timeout;
  private final Duration quiet;
  private final UsageMeter meter;

  Runner(
      Http http,
      Keycloak keycloak,
      String erpUrl,
      String agentUrl,
      Duration timeout,
      Duration quiet) {
    this.meter = new UsageMeter(http, agentUrl);
    this.http = http;
    this.keycloak = keycloak;
    this.erpUrl = erpUrl;
    this.agentUrl = agentUrl;
    this.timeout = timeout;
    this.quiet = quiet;
  }

  RunScore run(Scenario scenario, int repetition) {
    try {
      if (scenario.twist() == Scenario.Twist.FLAKY_ERP) {
        breakTheErp();
      }
      return attempt(scenario, repetition);
    } finally {
      // A fault left in place would poison every later run, even one that failed to set up.
      clearFaults();
    }
  }

  /**
   * Removes every injected fault; also called before the first run, in case an earlier eval died.
   */
  void clearFaults() {
    try {
      http.delete(erpUrl + "/admin/faults");
    } catch (IllegalStateException e) {
      log.warn("Could not clear the ERP's injected faults: {}", e.getMessage());
    }
  }

  /**
   * Reads the agent leans on fail some of the time: 503s, and a rate limit on the similar search.
   */
  private void breakTheErp() {
    http.put(
        erpUrl + "/admin/faults",
        Map.of("pathPattern", "/api/vendors/**", "latencyMillis", 0, "errorRate", 0.3));
    http.put(
        erpUrl + "/admin/faults",
        Map.of("pathPattern", "/api/purchase-orders/**", "latencyMillis", 0, "errorRate", 0.3));
    http.put(
        erpUrl + "/admin/faults",
        Map.of(
            "pathPattern",
            "/api/invoices/similar",
            "latencyMillis",
            0,
            "errorRate",
            0.5,
            "status",
            429));
  }

  private RunScore attempt(Scenario scenario, int repetition) {
    Instant started = Instant.now();
    boolean redeliveryDone = scenario.twist() != Scenario.Twist.REDELIVERED;
    Usage before = meter.read();
    JsonNode seeded = http.post(erpUrl + "/admin/scenarios/" + scenario.erpScenario());
    UUID exceptionId = UUID.fromString(seeded.path("exceptionIds").get(0).asString());
    log.info("{} #{}: exception {}", scenario.name(), repetition, exceptionId);
    JsonNode lastSeen = null;
    Set<String> answered = new HashSet<>();
    while (Duration.between(started, Instant.now()).compareTo(timeout) < 0) {
      Optional<JsonNode> view =
          http.get(agentUrl + "/api/cases/" + exceptionId, keycloak.tokenFor(OBSERVER));
      if (view.isPresent()) {
        lastSeen = view.get();
        if (!redeliveryDone
            && Duration.between(started, Instant.now()).compareTo(REDELIVER_AFTER) >= 0) {
          http.post(erpUrl + "/admin/exceptions/" + exceptionId + "/redeliver");
          log.info("  the ERP published the exception's event again");
          redeliveryDone = true;
        }
        decidePending(lastSeen);
        answerMail(lastSeen, scenario, answered);
        if (Settled.of(lastSeen, Instant.now(), quiet)) {
          break;
        }
      }
      sleep();
    }
    Usage after = meter.read();
    Usage usage =
        before == Usage.UNKNOWN || after == Usage.UNKNOWN ? Usage.UNKNOWN : after.since(before);
    Observed observed = observe(lastSeen, usage, Duration.between(started, Instant.now()));
    RunScore score = Scoring.score(scenario, repetition, observed);
    log.info(
        "{} #{}: {} actions={} routed={} tools={} usage={} passed={}",
        scenario.name(),
        repetition,
        observed.caseStatus(),
        observed.proposedActions(),
        observed.routedTo(),
        observed.toolsUsed(),
        observed.usage().byModel(),
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

  /** The vendor and buyer answer each message the desk sent them once, as the scenario scripts. */
  private void answerMail(JsonNode view, Scenario scenario, Set<String> answered) {
    for (JsonNode mail : view.path("mail")) {
      String messageId = mail.path("messageId").asString();
      String text = scenario.replies().get(mail.path("kind").asString());
      if (text == null || !answered.add(messageId)) {
        continue;
      }
      http.postJson(
          agentUrl + "/api/counterparty/replies",
          keycloak.tokenFor(OBSERVER),
          Map.of("messageId", messageId, "text", text));
      log.info(
          "  {} ({}) replied: {}",
          mail.path("recipient").asString(),
          mail.path("kind").asString(),
          text);
    }
  }

  private static Observed observe(JsonNode view, Usage usage, Duration wall) {
    if (view == null) {
      return new Observed("NEVER_OPENED", List.of(), List.of(), List.of(), List.of(), usage, wall);
    }
    List<String> actions = new ArrayList<>();
    List<String> routes = new ArrayList<>();
    for (JsonNode d : view.path("decisions")) {
      actions.add(d.path("action").asString());
      routes.add(d.path("requiredRole").asString());
    }
    List<String> tools = new ArrayList<>();
    List<String> mailed = new ArrayList<>();
    for (JsonNode event : view.path("timeline")) {
      String kind = event.path("kind").asString();
      if ("tool".equals(kind) || "mail-sent".equals(kind)) {
        // Both lines start with one word: the tool's name, or who the mail went to.
        String text = event.path("text").asString();
        int space = text.indexOf(' ');
        (kind.equals("tool") ? tools : mailed).add(space < 0 ? text : text.substring(0, space));
      }
    }
    return new Observed(
        view.path("status").asString(), actions, tools, routes, mailed, usage, wall);
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
