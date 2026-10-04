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
package org.jwcarman.nessyap.erp.audit;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.UUID;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The ERP's own record of every change: what, to which entity, by whom. */
@Component
public class AuditLog {

  private final JdbcClient jdbc;
  private final Clock clock;

  public AuditLog(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void append(Actor actor, String entityType, UUID entityId, String action, String detail) {
    jdbc.sql(
            """
            insert into erp_audit
                (id, at, entity_type, entity_id, action, acting_client, acting_user, detail)
            values (:id, :at, :entityType, :entityId, :action, :client, :user, :detail)
            """)
        .param("id", Ids.next())
        .param("at", Timestamp.from(clock.instant()))
        .param("entityType", entityType)
        .param("entityId", entityId)
        .param("action", action)
        .param("client", actor.client())
        .param("user", actor.user(), Types.VARCHAR)
        .param("detail", detail)
        .update();
  }
}
