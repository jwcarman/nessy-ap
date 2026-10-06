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
package org.jwcarman.nessyap.agent.oversight;

import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The desk publishes Nessy's own meter for each model call, so the alerts on cut-off answers and
 * failed calls read it, and the desk counts none of them again.
 */
class ModelMetricsTest extends ApAgentIntegrationTest {

  private static final String MODEL_CALLS = "gen_ai.client.operation.duration";

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired MeterRegistry registry;

  private double cutOff() {
    Timer timer =
        registry.find(MODEL_CALLS).tag("gen_ai.response.finish_reasons", "length").timer();
    return timer == null ? 0 : (double) timer.count();
  }

  @Test
  void an_answer_cut_off_at_the_output_limit_is_counted_by_nessys_meter() {
    double before = cutOff();
    UUID exceptionId = openCase();
    model.script(
        request -> new InferenceResult.Truncated(List.of(new Block.Text("The invoice is"))));

    agent.tell(caseIndex.agentFor(exceptionId), new CaseInput.PersonNote("clara", "look"));

    await().atMost(Duration.ofSeconds(20)).until(() -> cutOff() > before);
  }
}
