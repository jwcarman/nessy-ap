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
package org.jwcarman.nessyap.erp.outbox;

import java.sql.Timestamp;
import org.jwcarman.nessyap.contracts.ErpEvent;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an event is written down, in the same transaction as the change it describes, so the two
 * commit or vanish together. {@code OutboxPublisher} delivers it later.
 */
@Component
public class Outbox {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public Outbox(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(ErpEvent event) {
    jdbc.sql(
            """
            insert into outbox (id, event_type, payload, created_at)
            values (:id, :type, cast(:payload as jsonb), :createdAt)
            """)
        .param("id", event.eventId())
        .param("type", ErpEvents.routingKey(event))
        .param("payload", json.writeValueAsString(event))
        .param("createdAt", Timestamp.from(event.occurredAt()))
        .update();
  }
}
