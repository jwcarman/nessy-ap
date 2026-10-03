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
package org.jwcarman.nessyap.erp.resolution;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a command at most once per key. The key row is inserted first: a concurrent request with the
 * same key waits on that insert until the first commits, then reads its answer. A command that
 * fails rolls its key back with it, so failures are not remembered and a retry runs again.
 */
@Component
public class Idempotency {

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;

  public Idempotency(JdbcClient jdbc, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T execute(String key, String fingerprint, Class<T> type, Supplier<T> work) {
    int claimed =
        jdbc.sql(
                """
                insert into idempotency_record (idempotency_key, request_hash, created_at)
                values (:key, :hash, :createdAt)
                on conflict (idempotency_key) do nothing
                """)
            .param("key", key)
            .param("hash", fingerprint)
            .param("createdAt", Timestamp.from(clock.instant()))
            .update();
    if (claimed == 0) {
      Stored stored =
          jdbc.sql(
                  "select request_hash, response_body from idempotency_record where idempotency_key = :key")
              .param("key", key)
              .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2)))
              .single();
      if (!stored.requestHash().equals(fingerprint)) {
        throw new IdempotencyKeyReusedException(key);
      }
      return json.readValue(stored.responseBody(), type);
    }
    T result = work.get();
    jdbc.sql(
            """
            update idempotency_record set response_status = 200, response_body = :body
            where idempotency_key = :key
            """)
        .param("body", json.writeValueAsString(result))
        .param("key", key)
        .update();
    return result;
  }

  private record Stored(String requestHash, String responseBody) {}
}
