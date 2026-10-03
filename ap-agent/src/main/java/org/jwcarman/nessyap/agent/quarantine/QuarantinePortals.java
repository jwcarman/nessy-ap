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

import static org.jwcarman.nessyap.agent.quarantine.QuarantineAxes.INTEGRITY;

import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessyap.agent.quarantine.QuarantineAxes.Integrity;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ConfirmedPo;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;

/**
 * The quarantine's authority, declared once. Production declares from these methods and the tests
 * call the same methods, so a test exercises the real ceilings.
 *
 * <ul>
 *   <li>Mail enters UNENDORSED.
 *   <li>The agent may read a typed reading of it, never the text: an enum and a boolean carry no
 *       instructions, so the type is what makes an unendorsed value safe to show.
 *   <li>A model's reading stays UNENDORSED. The only lowering is a PO number that the ERP confirms
 *       for the case's vendor: agreement with something already trusted.
 *   <li>People who work cases read the text in the workbench; every reveal is on the record.
 * </ul>
 */
public final class QuarantinePortals {

  /** The access key that says the reader works cases (a deciding role or the auditor). */
  public static final String WORKS_CASES = "works-cases";

  private QuarantinePortals() {}

  public static Occlude<Reply> deskMail(Charter charter) {
    return charter.source("desk-mail", Untrusted.REPLY, Label.of(INTEGRITY, Integrity.UNENDORSED));
  }

  /** The agent's view of raw mail: refused, because nothing endorses what a sender wrote. */
  public static Reveal<Reply> agentReplies(Charter charter) {
    return charter.reveal("agent-replies", endorsedOnly(), Untrusted.REPLY);
  }

  /** People who work cases read the mail itself; anybody else is refused. */
  public static Reveal<Reply> workbenchReplies(Charter charter) {
    return charter.reveal(
        "workbench-replies", QuarantinePortals::peopleWhoWorkCases, Untrusted.REPLY);
  }

  /**
   * The agent reads a reading as a claim. Its ceiling admits UNENDORSED on purpose: the type holds
   * an enum, a pattern-checked number and a boolean, so it cannot carry an instruction.
   */
  public static Reveal<ReplyReading> agentReadings(Charter charter) {
    return charter.reveal(
        "agent-readings", Ceiling.of(INTEGRITY, Constraint.any()), Untrusted.READING);
  }

  /** Readings as facts: refused, because a model's reading of untrusted text is not a fact. */
  public static Reveal<ReplyReading> agentFacts(Charter charter) {
    return charter.reveal("agent-facts", endorsedOnly(), Untrusted.READING);
  }

  /** A confirmed PO number is a fact the agent may rely on. */
  public static Reveal<ConfirmedPo> agentConfirmedPos(Charter charter) {
    return charter.reveal("agent-confirmed-pos", endorsedOnly(), Untrusted.CONFIRMED_PO);
  }

  /** The quarantined reader. It reads what nobody vouched for, and its output stays unendorsed. */
  public static Derivation<Reply, ReplyReading> readReply(
      Charter charter, Function<Reply, ReplyReading> reader) {
    return charter.derivation(
        "reply.read",
        Untrusted.REPLY,
        Untrusted.READING,
        reader,
        d -> d.accepting(Ceiling.of(INTEGRITY, Constraint.any())));
  }

  /**
   * The one place trust is raised: a PO number the ERP holds for the case's vendor. The check is
   * against the ERP, never against the reply.
   */
  public static Derivation<ReplyReading, ConfirmedPo> confirmPo(
      Charter charter, Function<ReplyReading, Optional<ConfirmedPo>> erpHasIt) {
    return charter.checking(
        "reply.po.confirmed",
        Untrusted.READING,
        Untrusted.CONFIRMED_PO,
        (reading, ctx) -> erpHasIt.apply(reading),
        d ->
            d.accepting(Ceiling.of(INTEGRITY, Constraint.any()))
                .lowering(joined -> joined.with(INTEGRITY, Integrity.ENDORSED)));
  }

  private static Ceiling endorsedOnly() {
    return Ceiling.of(INTEGRITY, Constraint.atMost(Integrity.ENDORSED));
  }

  private static Ceiling peopleWhoWorkCases(AccessContext ctx) {
    return ctx.has(WORKS_CASES, "true")
        ? Ceiling.of(INTEGRITY, Constraint.any())
        : Ceiling.nothing();
  }
}
