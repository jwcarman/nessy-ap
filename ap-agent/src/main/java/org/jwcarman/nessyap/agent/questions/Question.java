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
package org.jwcarman.nessyap.agent.questions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A question the agent asked one person, and that person's answer once given.
 *
 * @param id the question
 * @param exceptionId the case it is about
 * @param askedOf the user name of the one person who may answer
 * @param text what the agent asked
 * @param choices the short answers offered; empty for an open question
 * @param askedAt when it was asked
 * @param answeredBy who answered, or null while it waits
 * @param choice the choice picked, or null
 * @param comment what the person wrote, or null
 * @param answeredAt when it was answered, or null while it waits
 */
public record Question(
    UUID id,
    UUID exceptionId,
    String askedOf,
    String text,
    List<String> choices,
    Instant askedAt,
    String answeredBy,
    String choice,
    String comment,
    Instant answeredAt) {

  public Question {
    choices = List.copyOf(choices);
  }

  public boolean answered() {
    return answeredAt != null;
  }
}
