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
package org.jwcarman.nessyap.eval;

import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Whether a case is finished for scoring: resolved, every decision answered, and the agent quiet
 * for a while since the last thing on its timeline. A case can resolve with a hold and then read a
 * reply that changes its mind; the quiet period lets that happen before the run is judged.
 */
final class Settled {

  /**
   * After a reply arrives the agent's next turn writes nothing until its first move, and a local
   * model can think for a long time first: wait at least this long after the last reply.
   */
  // Mail goes by SMTP, an IMAP poll and a reader that may take 30 seconds, behind other mail.
  static final Duration AFTER_REPLY = Duration.ofSeconds(120);

  private static final String STATUS = "status";
  private static final String TIMELINE = "timeline";

  private Settled() {}

  static boolean of(JsonNode view, Instant now, Duration quiet) {
    return of(view, now, quiet, null);
  }

  /**
   * As {@link #of(JsonNode, Instant, Duration)}, knowing when the evaluation last answered the
   * case: a case waiting for an answer the evaluation has just given is not done waiting until the
   * answer has had time to reach it.
   */
  static boolean of(JsonNode view, Instant now, Duration quiet, Instant lastAnswered) {
    String status = view.path(STATUS).asString();
    // An agent in the middle of a turn may still propose: nothing is final until it stops.
    if (view.path("agentActive").asBoolean(false)) {
      return false;
    }
    if ("INVESTIGATING".equals(status)) {
      return stalled(view, now);
    }
    // A case the desk put in front of a person is as finished as the agent will make it.
    if (!"RESOLVED".equals(status)
        && !"ON_HOLD".equals(status)
        && !"AWAITING_ANSWER".equals(status)
        && !"NEEDS_PERSON".equals(status)) {
      return false;
    }
    // An answer the evaluation gave is not in the case until its timeline shows it: mail goes by
    // SMTP and an IMAP poll. Whatever the case's status, it is not done until the answer lands.
    if (lastAnswered != null
        && lastAnswered.plus(AFTER_REPLY).isAfter(now)
        && !landedSince(view, lastAnswered)) {
      return false;
    }
    for (JsonNode decision : view.path("decisions")) {
      if (!"ANSWERED".equals(decision.path(STATUS).asString())) {
        return false;
      }
    }
    Instant last = Instant.EPOCH;
    String lastKind = "";
    for (JsonNode event : view.path(TIMELINE)) {
      Instant at = Instant.parse(event.path("at").asString());
      if (!at.isBefore(last)) {
        last = at;
        lastKind = event.path("kind").asString();
      }
    }
    Duration wait =
        "mail-received".equals(lastKind) && AFTER_REPLY.compareTo(quiet) > 0 ? AFTER_REPLY : quiet;
    return !last.plus(wait).isAfter(now);
  }

  /** Whether a reply or a person's answer reached the case's timeline at or after {@code since}. */
  private static boolean landedSince(JsonNode view, Instant since) {
    for (JsonNode event : view.path(TIMELINE)) {
      String kind = event.path("kind").asString();
      if (("mail-received".equals(kind) || "answer".equals(kind))
          && !Instant.parse(event.path("at").asString()).isBefore(since)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A case the agent left investigating, with nothing to decide and nobody asked, and no move for
   * longer than a reply takes: it is not going to move. It is settled so it can be scored, as a
   * failure, rather than waited on until the run times out.
   */
  private static boolean stalled(JsonNode view, Instant now) {
    for (JsonNode decision : view.path("decisions")) {
      if (!"ANSWERED".equals(decision.path(STATUS).asString())) {
        return false;
      }
    }
    for (JsonNode question : view.path("questions")) {
      if (!question.hasNonNull("answeredAt")) {
        return false;
      }
    }
    // A case its agent has not touched yet has no line at all: it is starting, not stalled.
    if (view.path(TIMELINE).isEmpty()) {
      return false;
    }
    Instant last = Instant.EPOCH;
    for (JsonNode event : view.path(TIMELINE)) {
      Instant at = Instant.parse(event.path("at").asString());
      if (at.isAfter(last)) {
        last = at;
      }
    }
    return !last.plus(AFTER_REPLY).isAfter(now);
  }
}
