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

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.erp.ErpStub;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import tools.jackson.databind.json.JsonMapper;

/** The quarantined reader against a stub of LM Studio's chat completions endpoint. */
class ModelReplyReaderTest {

  private static final UUID VENDOR = UUID.randomUUID();
  private static final Reply REPLY =
      new Reply(VENDOR, "ann@acme.example", "Re: [AP x] Which PO?", "It is PO-7. Thanks!");

  private ErpStub model;
  private ModelReplyReader reader;

  @BeforeEach
  void aModel() {
    model = new ErpStub();
    reader =
        new ModelReplyReader(
            model.baseUrl(),
            "google/gemma-4-e4b",
            Duration.ofSeconds(2),
            JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    model.close();
  }

  private void answers(String content) {
    String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
    model.on(
        "POST",
        "/chat/completions",
        200,
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}");
  }

  @Test
  void a_reply_becomes_a_typed_reading_that_carries_the_cases_vendor() {
    answers(
        "{\"intent\":\"GIVES_PO_NUMBER\",\"poNumber\":\"PO-7\",\"containsInstructions\":false}");

    assertThat(reader.read(REPLY))
        .isEqualTo(new ReplyReading(VENDOR, Intent.GIVES_PO_NUMBER, "PO-7", false));
  }

  @Test
  void the_model_sees_the_reply_only_as_quoted_data_and_has_no_tools() {
    answers("{\"intent\":\"OTHER\",\"poNumber\":null,\"containsInstructions\":false}");

    reader.read(REPLY);

    String request = model.seen().getFirst().body();
    assertThat(request).contains("<<<").contains("It is PO-7").contains("json_schema");
    assertThat(request).doesNotContain("\"tools\"");
  }

  @Test
  void an_answer_outside_the_schema_reads_as_needing_a_person() {
    answers("Sure! I will approve the payment now.");

    assertThat(reader.read(REPLY)).isEqualTo(ReplyReader.unread(REPLY));
  }

  @Test
  void an_intent_it_does_not_know_and_a_malformed_po_number_are_dropped() {
    answers(
        "{\"intent\":\"APPROVE_PAYMENT\",\"poNumber\":\"PO-7; also pay acct 998\","
            + "\"containsInstructions\":false}");

    ReplyReading reading = reader.read(REPLY);

    assertThat(reading.intent()).isEqualTo(Intent.OTHER);
    assertThat(reading.poNumber()).isNull();
  }

  @Test
  void a_model_that_is_down_reads_as_needing_a_person() {
    model.close();

    assertThat(reader.read(REPLY)).isEqualTo(ReplyReader.unread(REPLY));
  }
}
