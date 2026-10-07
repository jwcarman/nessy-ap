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
package org.jwcarman.nessyap.agent.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.jwcarman.nessyap.contracts.ReceiptPosted;

class CaseInputLabelsTest {

  private static final Pattern UUID_SHAPE =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final CaseInputLabels labels = new CaseInputLabels();

  private static MatchExceptionRaised raised(ReasonCode reason) {
    return new MatchExceptionRaised(
        UUID.randomUUID(),
        Instant.EPOCH,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "INV-1001",
        UUID.randomUUID(),
        "PO-1",
        reason,
        "Line 1 billed 10.40 against PO price 10.00 (+4.00%)",
        new BigDecimal("40.00"));
  }

  private static CaseInput.CounterpartyReply reply(Intent intent, boolean instructions) {
    return new CaseInput.CounterpartyReply(
        "ar@acme-fasteners.example", intent, List.of(), null, null, null, instructions);
  }

  @Test
  void a_rules_stop_is_labelled_by_reason_code_then_why() {
    assertThat(
            labels.stringify(
                new CaseInput.RulesStopped(raised(ReasonCode.NO_PO), "unhandled", "{}")))
        .isEqualTo("rules-stopped:NO_PO:unhandled");
  }

  @Test
  void a_reply_is_labelled_by_the_readers_intent() {
    assertThat(labels.stringify(reply(Intent.DENIES, false))).isEqualTo("reply:DENIES");
  }

  @Test
  void a_reply_that_tried_to_give_instructions_says_so() {
    assertThat(labels.stringify(reply(Intent.DENIES, true))).isEqualTo("reply:DENIES:instructions");
  }

  @Test
  void a_reply_the_reader_could_not_classify_is_labelled_unclear() {
    assertThat(labels.stringify(reply(null, false))).isEqualTo("reply:UNCLEAR");
  }

  @Test
  void an_answer_is_labelled_plainly() {
    assertThat(
            labels.stringify(
                new CaseInput.PersonAnswered("bob", "Was the price agreed?", "yes", "it was")))
        .isEqualTo("answered");
  }

  @Test
  void a_decision_is_labelled_by_action_then_outcome() {
    UUID decision = UUID.randomUUID();
    assertThat(labels.stringify(new CaseInput.DecisionApplied(decision, "hold", "applied")))
        .isEqualTo("decision:hold:applied");
    assertThat(labels.stringify(new CaseInput.DecisionApplied(decision, "hold", "declined")))
        .isEqualTo("decision:hold:declined");
    assertThat(
            labels.stringify(
                new CaseInput.DecisionApplied(
                    decision, "approve-variance", "applied after the approval had expired")))
        .isEqualTo("decision:approve-variance:applied-late");
  }

  @Test
  void a_receipt_a_note_and_a_raised_exception_have_their_own_labels() {
    assertThat(
            labels.stringify(
                new CaseInput.ReceiptArrived(
                    new ReceiptPosted(
                        UUID.randomUUID(), Instant.EPOCH, UUID.randomUUID(), "PO-7"))))
        .isEqualTo("receipt-arrived");
    assertThat(labels.stringify(new CaseInput.PersonNote("connie", "check receipt 2")))
        .isEqualTo("note");
    assertThat(labels.stringify(new CaseInput.ExceptionRaised(raised(ReasonCode.PRICE_VARIANCE))))
        .isEqualTo("exception-raised:PRICE_VARIANCE");
  }

  @Test
  void no_label_carries_an_id_a_name_or_free_text() {
    List<CaseInput> every =
        List.of(
            new CaseInput.ExceptionRaised(raised(ReasonCode.DUPLICATE)),
            new CaseInput.RulesStopped(raised(ReasonCode.ITEM_SUBSTITUTED), "exhausted", "{a=b}"),
            new CaseInput.ReceiptArrived(
                new ReceiptPosted(UUID.randomUUID(), Instant.EPOCH, UUID.randomUUID(), "PO-7")),
            new CaseInput.PersonNote("connie", "check receipt 2"),
            reply(Intent.GIVES_PO_NUMBER, true),
            new CaseInput.PersonAnswered("bob", "Was the price agreed?", "yes", "it was"),
            new CaseInput.DecisionApplied(UUID.randomUUID(), "reject", "declined"));

    for (CaseInput input : every) {
      String label = labels.stringify(input);
      assertThat(label).doesNotContainPattern(UUID_SHAPE);
      assertThat(label).doesNotContain("connie", "bob", "acme", "PO-7", "INV-1001", "receipt 2");
      assertThat(label).matches("[a-z-]+(:[A-Za-z0-9_-]+){0,2}");
    }
  }
}
