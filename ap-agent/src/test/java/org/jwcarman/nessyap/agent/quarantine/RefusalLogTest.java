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
package org.jwcarman.nessyap.agent.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.RefusalEvent;
import org.jwcarman.occlude.RefusalReason;
import org.jwcarman.occlude.storage.AuditRecord;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Every refusal at any gate reaches the log an operator reads, named by gate and reason. */
@ExtendWith(OutputCaptureExtension.class)
class RefusalLogTest {

  @Test
  void a_refusal_is_logged_with_its_gate_its_reason_and_the_value_id(CapturedOutput log) {
    new RefusalLog()
        .on(
            new RefusalEvent(
                Instant.now(),
                AuditRecord.Operation.REVEAL,
                "workbench.reply",
                "value-17",
                RefusalReason.ABOVE_CEILING,
                AccessContext.empty()));

    assertThat(log.getOut())
        .contains("WARN")
        .contains("REVEAL")
        .contains("workbench.reply")
        .contains("ABOVE_CEILING")
        .contains("value-17");
  }
}
