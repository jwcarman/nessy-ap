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
package org.jwcarman.nessyap.agent.quarantine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ConfirmedPo;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.AccessContextProvider;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.lattice.Axes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The quarantine's authority. The starter builds and binds the charter from the axes; this class
 * only declares what may be written and read, and who gets which portal.
 */
@Configuration(proxyBeanMethods = false)
public class QuarantineConfig {

  /** People who work cases: every deciding role, and the auditor. */
  private static final Set<String> WORK_CASES =
      Set.of("ap-clerk", "buyer", "ap-manager", "controller", "auditor");

  @Bean
  public Axes quarantineAxes() {
    return QuarantineAxes.axes();
  }

  /** The quarantined reader: a small model, no tools, an answer held to a schema. */
  @Bean
  @ConditionalOnProperty(
      name = "ap.quarantine.reader.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ReplyReader modelReplyReader(
      @Value("${ap.quarantine.reader.base-url}") String baseUrl,
      @Value("${ap.quarantine.reader.model}") String model,
      @Value("${ap.quarantine.reader.timeout}") Duration timeout) {
    return new ModelReplyReader(baseUrl, model, timeout, JsonMapper.builder().build());
  }

  /** With the reader switched off, nothing in a reply is known and a person must read it. */
  @Bean
  @ConditionalOnMissingBean
  public ReplyReader unreadReplies() {
    return ReplyReader::unread;
  }

  @Bean
  public Quarantine quarantine(Charter charter, ReplyReader reader, ErpClient erp) {
    return new Quarantine(
        QuarantinePortals.deskMail(charter),
        QuarantinePortals.readReply(charter, reader::read),
        QuarantinePortals.confirmPo(charter, reading -> confirmedFor(erp, reading)),
        QuarantinePortals.agentReadings(charter),
        QuarantinePortals.agentConfirmedPos(charter),
        QuarantinePortals.workbenchReplies(charter));
  }

  /**
   * Who is asking, read from Spring Security at the gate. Signed-in people who work cases may read
   * quarantined text; the agent and the background routes carry no person, so they may not.
   */
  @Bean
  public AccessContextProvider deskAccess() {
    return () -> {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated()) {
        return AccessContext.empty();
      }
      Map<String, String> who = new LinkedHashMap<>();
      who.put("principal", auth.getName());
      if (RealmRoles.of(auth).stream().anyMatch(WORK_CASES::contains)) {
        who.put(QuarantinePortals.WORKS_CASES, "true");
      }
      return AccessContext.of(who);
    };
  }

  /** The ERP, not the reply, decides: the PO must exist and belong to the case's vendor. */
  private static Optional<ConfirmedPo> confirmedFor(ErpClient erp, ReplyReading reading) {
    if (reading.poNumber() == null || reading.vendorId() == null) {
      return Optional.empty();
    }
    if (erp.purchaseOrder(reading.poNumber()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode po)
        && reading.vendorId().toString().equals(po.path("vendorId").asString())) {
      return Optional.of(new ConfirmedPo(reading.poNumber()));
    }
    return Optional.empty();
  }
}
