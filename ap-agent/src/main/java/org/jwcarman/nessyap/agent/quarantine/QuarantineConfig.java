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
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ConfirmedPo;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ModelReading;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The quarantine's authority. The starter builds and binds the charter from the axes; this class
 * only declares what may be written and read, and who gets which portal.
 */
@Configuration(proxyBeanMethods = false)
public class QuarantineConfig {

  /** People who work cases: every deciding role, and the auditor. */
  private static final Set<String> WORK_CASES =
      Set.of("ap-clerk", "buyer", "ap-manager", "controller", "auditor");

  /** The reader's agent type: one per application, a fresh agent for each reply. */
  static final AgentType READER = new AgentType("reply-reader");

  @Bean
  public Axes quarantineAxes() {
    return QuarantineAxes.axes();
  }

  /**
   * The quarantined reader: a Nessy direct harness with no tools and a typed answer, using the
   * application's providers and a small model of its own. Its history is stored like any agent's,
   * so each read is part of the record.
   */
  @Bean
  @ConditionalOnProperty(
      name = "ap.quarantine.reader.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ReplyReader modelReplyReader(
      DirectHarnessFactory harnesses,
      @Value("${ap.quarantine.reader.provider}") String provider,
      @Value("${ap.quarantine.reader.model}") String model,
      @Value("${ap.quarantine.reader.timeout}") Duration timeout,
      PlatformTransactionManager transactions) {
    DirectHarness<Reply, ModelReading> reader =
        harnesses.create(
            READER,
            ModelReading.class,
            harness ->
                harness
                    .systemPrompt(ModelReplyReader.INSTRUCTIONS)
                    .inputRenderer(ModelReplyReader::render)
                    .inference(
                        inference ->
                            inference
                                .provider(provider)
                                .model(model)
                                .maxTokens(256)
                                .timeout(timeout)));
    TransactionTemplate outsideTransaction = new TransactionTemplate(transactions);
    outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    return new ModelReplyReader(reader, outsideTransaction);
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
    if (erp.purchaseOrder(reading.poNumber().value())
            instanceof ErpOutcome.Ok<JsonNode>(JsonNode po)
        && reading.vendorId().toString().equals(po.path("vendorId").asString())) {
      return Optional.of(new ConfirmedPo(reading.poNumber()));
    }
    return Optional.empty();
  }
}
