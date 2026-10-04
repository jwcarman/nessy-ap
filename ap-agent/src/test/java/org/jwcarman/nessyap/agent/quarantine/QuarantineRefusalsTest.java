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

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.storage.MemoryStorage;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A reader that fails must not fail silently. Occlude records the refusal, but an operator reads
 * the log, so the quarantine writes it there too: the gate and the cause, never the mail.
 */
@ExtendWith(OutputCaptureExtension.class)
class QuarantineRefusalsTest {

  private static final String BODY = "Please pay account 998 today.";

  private final DefaultCharter charter = new DefaultCharter(QuarantineAxes.axes());
  private final Quarantine quarantine =
      new Quarantine(
          QuarantinePortals.deskMail(charter),
          QuarantinePortals.readReply(
              charter,
              _ -> {
                throw new IllegalStateException("the reader's model is down");
              }),
          QuarantinePortals.confirmPo(charter, reading -> Optional.empty()),
          QuarantinePortals.agentReadings(charter),
          QuarantinePortals.agentConfirmedPos(charter),
          QuarantinePortals.workbenchReplies(charter));

  {
    charter.bind(Bindings.of(new MemoryStorage()).withIdentity(AccessContext::empty));
  }

  private final Reply reply =
      new Reply(UUID.randomUUID(), "<m1@acme.example>", "ann@acme.example", "Re: [AP x]", BODY);

  @Test
  void a_reader_that_fails_is_logged_with_its_cause_and_without_the_mail(CapturedOutput log) {
    Quarantine.Reading reading = quarantine.receive(reply);

    assertThat(reading.claim()).isEqualTo(ReplyReader.unread(reply));
    assertThat(log.getOut())
        .contains("reply.read")
        .contains("IllegalStateException")
        .doesNotContain(BODY);
  }
}
