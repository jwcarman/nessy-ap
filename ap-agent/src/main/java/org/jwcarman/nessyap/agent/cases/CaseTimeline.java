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
package org.jwcarman.nessyap.agent.cases;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What happened on a case, in order, written by the app as it happens. Narration is told once to
 * whoever is listening; this is the copy that survives a restart and that people and the eval read.
 */
@Component
public class CaseTimeline {

  /**
   * One line of a case's history. A line about mail carries the mail's quarantine handle: the text
   * itself is never stored here, and a person reads it through the workbench.
   */
  public record CaseEvent(Instant at, String kind, String text, String mailHandle) {}

  private final JdbcClient jdbc;
  private final Clock clock;

  public CaseTimeline(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void record(UUID exceptionId, String kind, String text) {
    record(exceptionId, kind, text, null);
  }

  /** A line about quarantined mail: what the desk knows of it, and the handle to read it. */
  public void record(UUID exceptionId, String kind, String text, String mailHandle) {
    jdbc.sql(
            """
            insert into case_event (exception_id, at, kind, text, mail_handle)
            values (:id, :at, :kind, :text, :handle)
            """)
        .param("id", exceptionId)
        .param("at", Timestamp.from(clock.instant()))
        .param("kind", kind)
        .param("text", text)
        .param("handle", mailHandle, Types.VARCHAR)
        .update();
  }

  public List<CaseEvent> of(UUID exceptionId) {
    return jdbc.sql(
            "select at, kind, text, mail_handle from case_event where exception_id = :id order by id")
        .param("id", exceptionId)
        .query(
            (rs, row) ->
                new CaseEvent(
                    rs.getTimestamp("at").toInstant(),
                    rs.getString("kind"),
                    rs.getString("text"),
                    rs.getString("mail_handle")))
        .list();
  }
}
