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
package org.jwcarman.nessyap.agent.api;

import org.jwcarman.nessyap.agent.decisions.PolicyConfig;
import org.jwcarman.nessyap.agent.mail.Counterparty;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Development only: lets the evaluation answer the desk's mail as its recipient, and write to the
 * desk unprompted, as a fraudster would.
 */
@RestController
@ConditionalOnProperty(name = "ap.counterparty.enabled", havingValue = "true")
public class CounterpartyApi {

  /** An answer to one message the desk sent, named by its Message-ID. */
  public record Reply(String messageId, String text) {}

  /** A message to the desk that answers nothing it sent. */
  public record Unprompted(String from, String subject, String text) {}

  private final Counterparty counterparty;

  public CounterpartyApi(Counterparty counterparty) {
    this.counterparty = counterparty;
  }

  @PostMapping("/api/counterparty/replies")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void reply(@RequestBody Reply reply, Authentication caller) {
    requireDecider(caller);
    Counterparty.Sent original =
        counterparty
            .findByMessageId(reply.messageId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "The desk sent no such mail"));
    counterparty.reply(original, reply.text());
  }

  @PostMapping("/api/counterparty/unsolicited")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void unsolicited(@RequestBody Unprompted mail, Authentication caller) {
    requireDecider(caller);
    counterparty.writeUnprompted(mail.from(), mail.subject(), mail.text());
  }

  private static void requireDecider(Authentication caller) {
    if (RealmRoles.of(caller).stream().noneMatch(PolicyConfig.DECIDING_ROLES::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not for this role");
    }
  }
}
