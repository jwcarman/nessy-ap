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

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The inputs {@link GuardedAgents} held back: where they are kept, and how they are released. */
@Component
public class HeldInputs {

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public HeldInputs(JdbcClient jdbc, JsonMapper json, Clock clock, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.tx = tx;
  }

  /** Keeps an input that was not told to its agent, and why. */
  void add(AgentId agentId, UUID exceptionId, String reason, CaseInput input) {
    jdbc.sql(
            """
            insert into held_input (id, agent_id, exception_id, reason, input, held_at)
            values (:id, :agentId, :exceptionId, :reason, :input, :at)
            """)
        .param("id", Ids.next())
        .param("agentId", agentId.value())
        .param("exceptionId", exceptionId)
        .param("reason", reason)
        .param("input", json.writeValueAsString(input))
        .param("at", Timestamp.from(clock.instant()))
        .update();
  }

  /**
   * Hands each unreleased input held for this reason to {@code tell}, oldest first. Each one is
   * marked released in the same transaction that tells it.
   *
   * @return how many inputs were handed over
   */
  int release(String reason, BiConsumer<AgentId, CaseInput> tell) {
    List<Held> held =
        jdbc.sql(
                """
                select id, agent_id, input from held_input
                where reason = :reason and released_at is null
                order by held_at
                """)
            .param("reason", reason)
            .query(
                (rs, row) ->
                    new Held(
                        rs.getObject("id", UUID.class),
                        new AgentId(rs.getObject("agent_id", UUID.class)),
                        rs.getString("input")))
            .list();
    for (Held h : held) {
      tx.executeWithoutResult(
          _ -> {
            jdbc.sql("update held_input set released_at = :at where id = :id")
                .param("at", Timestamp.from(clock.instant()))
                .param("id", h.id())
                .update();
            tell.accept(h.agentId(), json.readValue(h.input(), CaseInput.class));
          });
    }
    return held.size();
  }

  private record Held(UUID id, AgentId agentId, String input) {}
}
