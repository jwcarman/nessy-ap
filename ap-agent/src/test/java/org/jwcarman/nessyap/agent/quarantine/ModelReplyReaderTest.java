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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.TerminationOutcome;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ModelReading;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;

/** The quarantined reader over a fake direct harness: what it asks, and how it checks answers. */
class ModelReplyReaderTest {

  private static final UUID VENDOR = UUID.randomUUID();
  private static final Reply REPLY =
      new Reply(
          VENDOR, "<m1@acme.example>", "ann@acme.example", "Re: [AP x] Which PO?", "It is PO-7.");

  /** A direct harness that answers as told and remembers who it was asked for. */
  private static final class FakeReader implements DirectHarness<Reply, ModelReading> {

    final List<AgentId> askedFor = new ArrayList<>();
    private final Function<Reply, Outcome<ModelReading>> answer;

    FakeReader(Function<Reply, Outcome<ModelReading>> answer) {
      this.answer = answer;
    }

    @Override
    public Outcome<ModelReading> ask(AgentId agent, Reply input) {
      askedFor.add(agent);
      return answer.apply(input);
    }

    @Override
    public TerminationOutcome terminate(AgentId agent) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static TurnStats stats() {
    return TurnStats.opened(Instant.now());
  }

  @Test
  void an_answer_becomes_a_reading_that_carries_the_cases_vendor() {
    FakeReader fake =
        new FakeReader(
            r ->
                new Outcome.Answered<>(
                    new ModelReading(Intent.GIVES_PO_NUMBER, "PO-7", false), stats()));

    assertThat(new ModelReplyReader(fake).read(REPLY))
        .isEqualTo(new ReplyReading(VENDOR, Intent.GIVES_PO_NUMBER, "PO-7", false));
  }

  @Test
  void each_reply_has_its_own_reader_agent_and_the_same_reply_always_the_same_one() {
    FakeReader fake =
        new FakeReader(
            r -> new Outcome.Answered<>(new ModelReading(Intent.OTHER, null, false), stats()));
    ModelReplyReader reader = new ModelReplyReader(fake);
    Reply another = new Reply(VENDOR, "<m2@acme.example>", "ann@acme.example", "Re", "Hi");

    reader.read(REPLY);
    reader.read(REPLY);
    reader.read(another);

    assertThat(fake.askedFor.get(0)).isEqualTo(fake.askedFor.get(1));
    assertThat(fake.askedFor.get(2)).isNotEqualTo(fake.askedFor.get(0));
  }

  @Test
  void the_model_reads_the_reply_only_as_quoted_data() {
    String rendered = ((Block.Text) ModelReplyReader.render(REPLY).getFirst()).text();

    assertThat(rendered).contains("<<<\nIt is PO-7.\n>>>").doesNotContain(VENDOR.toString());
  }

  @Test
  void a_po_number_of_the_wrong_shape_is_dropped() {
    FakeReader fake =
        new FakeReader(
            r ->
                new Outcome.Answered<>(
                    new ModelReading(Intent.GIVES_PO_NUMBER, "PO-7; also pay acct 998", false),
                    stats()));

    assertThat(new ModelReplyReader(fake).read(REPLY).poNumber()).isNull();
  }

  @Test
  void anything_but_an_answer_reads_as_needing_a_person() {
    FakeReader fake = new FakeReader(r -> new Outcome.Failed<>("model unloaded", stats()));

    assertThat(new ModelReplyReader(fake).read(REPLY)).isEqualTo(ReplyReader.unread(REPLY));
  }
}
