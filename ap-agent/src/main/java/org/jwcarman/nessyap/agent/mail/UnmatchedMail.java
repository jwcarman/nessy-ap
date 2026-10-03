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
package org.jwcarman.nessyap.agent.mail;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Mail the desk received that no case claimed, newest first, for a person to sort out. */
@Component
public class UnmatchedMail {

  /** One message nobody's case claimed. */
  public record Unmatched(String sender, String subject, String body, Instant receivedAt) {}

  private final JdbcClient jdbc;

  public UnmatchedMail(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Unmatched> recent(int limit) {
    return jdbc.sql(
            """
            select sender, subject, body, received_at from unmatched_mail
            order by received_at desc limit :limit
            """)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new Unmatched(
                    rs.getString("sender"),
                    rs.getString("subject"),
                    rs.getString("body"),
                    rs.getTimestamp("received_at").toInstant()))
        .list();
  }
}
