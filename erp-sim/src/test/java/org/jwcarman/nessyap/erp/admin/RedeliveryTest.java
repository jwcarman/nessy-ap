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
package org.jwcarman.nessyap.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The eval's way to see what a duplicate delivery does to the agent: the same event, again. */
class RedeliveryTest extends ErpIntegrationTest {

  @Autowired ScenarioCatalog catalog;
  @Autowired WebApplicationContext web;

  private Optional<Instant> publishedAt(UUID exceptionId) {
    return jdbc.sql(
            """
            select published_at from outbox
            where event_type = 'match-exception.raised' and payload->>'exceptionId' = :id
            """)
        .param("id", exceptionId.toString())
        .query(
            (rs, row) ->
                Optional.ofNullable(rs.getTimestamp("published_at")).map(t -> t.toInstant()))
        .single();
  }

  private long rows(UUID exceptionId) {
    return jdbc.sql(
            """
            select count(*) from outbox
            where event_type = 'match-exception.raised' and payload->>'exceptionId' = :id
            """)
        .param("id", exceptionId.toString())
        .query(Long.class)
        .single();
  }

  @Test
  void a_redelivered_exception_is_published_again_under_the_same_event() throws Exception {
    UUID exceptionId = catalog.load("duplicate").exceptionIds().getFirst();
    Instant first =
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> publishedAt(exceptionId), Optional::isPresent)
            .orElseThrow();

    MockMvcBuilders.webAppContextSetup(web)
        .build()
        .perform(post("/admin/exceptions/{id}/redeliver", exceptionId))
        .andExpect(status().isNoContent());

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> publishedAt(exceptionId), at -> at.isPresent() && at.get().isAfter(first));
    assertThat(rows(exceptionId)).isEqualTo(1);
  }
}
