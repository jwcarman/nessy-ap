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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The switches people use to oversee the agents. Each is stored, so it survives a restart, and
 * every change is kept with who made it and when.
 */
@Component
public class Switches {

  /** While on, no agent is told anything: what would have reached one is held for a person. */
  public static final String AGENTS_PAUSED = "agents-paused";

  private final JdbcClient jdbc;
  private final Clock clock;

  public Switches(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public boolean on(String name) {
    return jdbc.sql("select on_now from oversight_switch where name = :name")
        .param("name", name)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  /** Sets a switch, and records the change. */
  public void set(String name, boolean on, String by) {
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.sql(
            """
            insert into oversight_switch (name, on_now, changed_by, changed_at)
            values (:name, :on, :by, :at)
            on conflict (name) do update
              set on_now = excluded.on_now,
                  changed_by = excluded.changed_by,
                  changed_at = excluded.changed_at
            """)
        .param("name", name)
        .param("on", on)
        .param("by", by)
        .param("at", now)
        .update();
    jdbc.sql(
            """
            insert into oversight_change (name, on_now, changed_by, changed_at)
            values (:name, :on, :by, :at)
            """)
        .param("name", name)
        .param("on", on)
        .param("by", by)
        .param("at", now)
        .update();
  }
}
