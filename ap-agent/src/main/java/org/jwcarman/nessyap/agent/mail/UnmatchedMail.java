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
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Mail the desk received that no case claimed, newest first, for a person to sort out. The table
 * keeps only quarantine handles; each message is revealed to the person asking, and every reveal is
 * on the quarantine's record.
 */
@Component
public class UnmatchedMail {

  /** One message nobody's case claimed, as the person asking may read it. */
  public record Unmatched(String sender, String subject, String body, Instant receivedAt) {}

  private final JdbcClient jdbc;
  private final Quarantine quarantine;

  public UnmatchedMail(JdbcClient jdbc, Quarantine quarantine) {
    this.jdbc = jdbc;
    this.quarantine = quarantine;
  }

  public List<Unmatched> recent(int limit) {
    record Held(String handle, Instant receivedAt) {}
    return jdbc
        .sql(
            """
            select mail_handle, received_at from unmatched_mail
            order by received_at desc limit :limit
            """)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new Held(rs.getString("mail_handle"), rs.getTimestamp("received_at").toInstant()))
        .list()
        .stream()
        .flatMap(
            held ->
                quarantine.forPerson(held.handle()).stream()
                    .map(
                        reply ->
                            new Unmatched(
                                reply.sender(), reply.subject(), reply.body(), held.receivedAt())))
        .toList();
  }
}
