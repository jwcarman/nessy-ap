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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The quarantined reader as the desk runs it: Nessy's direct harness, the desk's own store, and a
 * scripted model in place of the real one. The mail route reads inside its transaction, so one test
 * does too.
 */
@TestPropertySource(properties = "ap.quarantine.reader.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReaderWiredTest extends ApAgentIntegrationTest {

  @Autowired Quarantine quarantine;
  @Autowired ReplyReader reader;
  @Autowired TransactionTemplate transactions;

  private static Reply reply() {
    return new Reply(
        UUID.randomUUID(),
        "<" + UUID.randomUUID() + "@acme.example>",
        "ann@acme.example",
        "Re: [AP x] Which PO?",
        "It is PO-7.");
  }

  private void modelAnswers(String json) {
    model.script(request -> new InferenceResult.Answer(List.of(new Block.Text(json))));
  }

  @Test
  void the_desk_reads_replies_with_the_model() {
    assertThat(reader).isInstanceOf(ModelReplyReader.class);
  }

  @Test
  void a_reply_is_read_by_the_model() {
    modelAnswers(
        "{\"intent\":\"GIVES_PO_NUMBER\",\"poNumber\":\"PO-7\",\"containsInstructions\":false}");

    Quarantine.Reading reading = quarantine.receive(reply());

    assertThat(reading.claim().intent()).isEqualTo(Intent.GIVES_PO_NUMBER);
    assertThat(reading.claim().poNumber()).isEqualTo(new PoNumber("PO-7"));
  }

  @Test
  void a_reply_is_read_by_the_model_inside_the_mail_routes_transaction() {
    modelAnswers(
        "{\"intent\":\"GIVES_PO_NUMBER\",\"poNumber\":\"PO-7\",\"containsInstructions\":false}");

    Quarantine.Reading reading = transactions.execute(status -> quarantine.receive(reply()));

    assertThat(reading.claim().intent()).isEqualTo(Intent.GIVES_PO_NUMBER);
  }
}
