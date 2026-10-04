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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogTest extends ErpIntegrationTest {

  @Autowired AuditLog auditLog;

  @Test
  void records_who_did_what_to_which_entity() {
    UUID entity = Ids.next();

    auditLog.append(new Actor("workbench", "connie"), "invoice", entity, "hold", "waiting on PO");

    Map<String, Object> row =
        jdbc.sql("select * from erp_audit where entity_id = :id")
            .param("id", entity)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("entity_type", "invoice")
        .containsEntry("action", "hold")
        .containsEntry("acting_client", "workbench")
        .containsEntry("acting_user", "connie")
        .containsEntry("detail", "waiting on PO");
    assertThat(row.get("at")).isNotNull();
  }

  @Test
  void records_an_anonymous_caller_with_no_user() {
    UUID entity = Ids.next();

    auditLog.append(Actor.anonymous(), "invoice", entity, "received", "d");

    Map<String, Object> row =
        jdbc.sql("select * from erp_audit where entity_id = :id")
            .param("id", entity)
            .query()
            .singleRow();
    assertThat(row).containsEntry("acting_client", "anonymous");
    assertThat(row.get("acting_user")).isNull();
  }
}
