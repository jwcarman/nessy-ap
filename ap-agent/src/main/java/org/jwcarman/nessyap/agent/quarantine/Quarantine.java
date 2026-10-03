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

import java.util.Optional;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ConfirmedPo;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Derived;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.Revealed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The desk's quarantine for mail. It holds each reply where nothing but a person and the
 * quarantined reader can read it, and gives the agent only a typed reading and, where the ERP
 * agrees, a confirmed PO number.
 */
public class Quarantine {

  private static final Logger log = LoggerFactory.getLogger(Quarantine.class);

  /** What the agent may know about one reply: a claim, and a fact if the ERP confirmed one. */
  public record Reading(Occluded<Reply> reply, ReplyReading claim, Optional<String> confirmedPo) {}

  private final Occlude<Reply> deskMail;
  private final Derivation<Reply, ReplyReading> readReply;
  private final Derivation<ReplyReading, ConfirmedPo> confirmPo;
  private final Reveal<ReplyReading> agentReadings;
  private final Reveal<ConfirmedPo> agentConfirmedPos;
  private final Reveal<Reply> workbenchReplies;

  public Quarantine(
      Occlude<Reply> deskMail,
      Derivation<Reply, ReplyReading> readReply,
      Derivation<ReplyReading, ConfirmedPo> confirmPo,
      Reveal<ReplyReading> agentReadings,
      Reveal<ConfirmedPo> agentConfirmedPos,
      Reveal<Reply> workbenchReplies) {
    this.deskMail = deskMail;
    this.readReply = readReply;
    this.confirmPo = confirmPo;
    this.agentReadings = agentReadings;
    this.agentConfirmedPos = agentConfirmedPos;
    this.workbenchReplies = workbenchReplies;
  }

  /** Holds a reply and reads it for the agent. A refusal anywhere reads as "a person must look". */
  public Reading receive(Reply reply) {
    Occluded<Reply> held = deskMail.occlude(reply);
    Occluded<ReplyReading> made;
    switch (readReply.derive(held)) {
      case Derived.Made<ReplyReading>(Occluded<ReplyReading> reading) -> made = reading;
      case Derived.Refused<ReplyReading>(Derived.Reason reason, String detail) -> {
        // Occlude's detail names the gate and the cause, never the value.
        log.warn(
            "Reply {} was not read ({}: {}); a person must read it", held.id(), reason, detail);
        return new Reading(held, ReplyReader.unread(reply), Optional.empty());
      }
    }
    ReplyReading claim =
        agentReadings.reveal(made) instanceof Revealed.Allowed<ReplyReading> allowed
            ? allowed.plaintext()
            : ReplyReader.unread(reply);
    Optional<String> confirmed =
        confirmPo.derive(made) instanceof Derived.Made<ConfirmedPo>(Occluded<ConfirmedPo> po)
                && agentConfirmedPos.reveal(po) instanceof Revealed.Allowed<ConfirmedPo> fact
            ? Optional.of(fact.plaintext().poNumber().value())
            : Optional.empty();
    return new Reading(held, claim, confirmed);
  }

  /** Holds mail that answers no case, unread, for a person to sort out. Returns its handle. */
  public String hold(Reply reply) {
    return deskMail.occlude(reply).id();
  }

  /** The reply itself, for a person who works cases; empty for anybody else. */
  public Optional<Reply> forPerson(String handle) {
    return workbenchReplies.reveal(new Occluded<>(handle))
            instanceof Revealed.Allowed<Reply> allowed
        ? Optional.of(allowed.plaintext())
        : Optional.empty();
  }
}
