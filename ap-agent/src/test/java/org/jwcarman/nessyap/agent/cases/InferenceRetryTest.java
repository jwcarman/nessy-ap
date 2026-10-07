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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A model stream that drops once must not end the turn. In the full run of 2026-10-07 four turns
 * ended with "no answer from the model: Stream failed" within twelve seconds, none was retried, and
 * two cases went to a person with no proposal.
 */
class InferenceRetryTest extends ApAgentIntegrationTest {

  @Autowired QueuedHarness<CaseInput> agent;

  @Test
  void an_unknown_inference_failure_is_tried_again_and_the_turn_answers() {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    AtomicInteger calls = new AtomicInteger();
    model.script(
        request ->
            calls.incrementAndGet() == 1
                ? new InferenceResult.Fault(new Failure.Unknown("Stream failed"))
                : new InferenceResult.Answer(List.of(new Block.Text("Noted."))));

    agent.tell(agentId, new CaseInput.PersonNote("clara", "look at this"));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> narration.count(agentId, Narration.TurnEnding.class) == 1);
    assertThat(narration.count(agentId, Narration.Answered.class)).isEqualTo(1);
    assertThat(narration.count(agentId, Narration.TurnFailed.class)).isZero();
    assertThat(calls.get()).isEqualTo(2);
  }
}
