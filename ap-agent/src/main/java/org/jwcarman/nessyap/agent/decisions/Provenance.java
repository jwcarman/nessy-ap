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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessyap.agent.resolver.Resolver;
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * What produced a proposal, so that "why did the desk do this?" can be answered later: who proposed
 * it, which models, which build of the desk, and which version of the playbook, the decision tables
 * and the routing policy. Each document is named by the first 12 hex digits of its SHA-256.
 */
@Component
public class Provenance {

  /**
   * One proposal's provenance.
   *
   * @param proposer {@code rules} or {@code agent}
   * @param agentModel the agent's model, or null for a proposal from the rules
   * @param readerModel the model that reads vendor replies
   * @param deskBuild the desk's git commit, or {@code unknown}
   * @param playbook the agent's playbook
   * @param rules the decision tables
   * @param policy the routing policy as OPA holds it now, or {@code unknown} if OPA did not answer
   */
  public record Stamp(
      String proposer,
      String agentModel,
      String readerModel,
      String deskBuild,
      String playbook,
      String rules,
      String policy) {}

  private static final Logger log = LoggerFactory.getLogger(Provenance.class);
  private static final String UNKNOWN = "unknown";

  private final String agentModel;
  private final String readerModel;
  private final String deskBuild;
  private final String playbook;
  private final String rules;
  private final RestClient opa;

  public Provenance(
      @Value("${nessy.model}") String agentModel,
      @Value("${ap.quarantine.reader.model}") String readerModel,
      @Value("${ap.opa.url}") String opaUrl,
      ObjectProvider<GitProperties> git) {
    this.agentModel = agentModel;
    this.readerModel = readerModel;
    GitProperties properties = git.getIfAvailable();
    this.deskBuild =
        properties == null || properties.getShortCommitId() == null
            ? UNKNOWN
            : properties.getShortCommitId();
    this.playbook = digest(read("prompts/ap-playbook.md"));
    this.rules = digest(read(Resolver.TABLES));
    this.opa = RestClient.create(opaUrl);
  }

  /** The provenance of a proposal made now, by this proposer. */
  public Stamp stamp(AgentType proposer) {
    boolean byRules = ResolverDesk.RULES.equals(proposer);
    return new Stamp(
        byRules ? "rules" : "agent",
        byRules ? null : agentModel,
        readerModel,
        deskBuild,
        playbook,
        rules,
        policy());
  }

  /** Every policy module OPA holds, in id order: the policy that routed this proposal. */
  private String policy() {
    try {
      JsonNode answer = opa.get().uri("/v1/policies").retrieve().body(JsonNode.class);
      Map<String, String> modules = new TreeMap<>();
      if (answer != null) {
        answer
            .path("result")
            .forEach(m -> modules.put(m.path("id").asString(), m.path("raw").asString()));
      }
      StringBuilder all = new StringBuilder();
      modules.forEach((id, raw) -> all.append(id).append('\n').append(raw).append('\n'));
      return modules.isEmpty() ? UNKNOWN : digest(all.toString());
    } catch (RestClientException e) {
      log.warn(
          "Could not read the policy from OPA for a proposal's provenance: {}", e.getMessage());
      return UNKNOWN;
    }
  }

  private static String read(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  static String digest(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }
}
