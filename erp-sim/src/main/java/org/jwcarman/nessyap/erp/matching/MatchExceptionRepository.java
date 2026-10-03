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
package org.jwcarman.nessyap.erp.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MatchExceptionRepository {

  private final JdbcClient jdbc;

  public MatchExceptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(MatchException exception) {
    jdbc.sql(
            """
            insert into match_exception
                (id, invoice_id, reason_code, summary, amount_at_issue, status, raised_at)
            values (:id, :invoiceId, :reasonCode, :summary, :amount, :status, :raisedAt)
            """)
        .param("id", exception.id())
        .param("invoiceId", exception.invoiceId())
        .param("reasonCode", exception.reasonCode().name())
        .param("summary", exception.summary())
        .param("amount", exception.amountAtIssue())
        .param("status", exception.status().name())
        .param("raisedAt", Timestamp.from(exception.raisedAt()))
        .update();
  }

  public Optional<MatchException> find(UUID id) {
    return jdbc.sql("select * from match_exception where id = :id")
        .param("id", id)
        .query(MatchExceptionRepository::exception)
        .optional();
  }

  public List<MatchException> findByInvoice(UUID invoiceId) {
    return jdbc.sql(
            "select * from match_exception where invoice_id = :invoiceId order by raised_at, id")
        .param("invoiceId", invoiceId)
        .query(MatchExceptionRepository::exception)
        .list();
  }

  public List<MatchException> findByStatus(ExceptionStatus status) {
    return jdbc.sql("select * from match_exception where status = :status order by raised_at, id")
        .param("status", status.name())
        .query(MatchExceptionRepository::exception)
        .list();
  }

  public int resolveOpenForInvoice(UUID invoiceId, Instant at) {
    return jdbc.sql(
            """
            update match_exception set status = 'RESOLVED', resolved_at = :at
            where invoice_id = :invoiceId and status = 'OPEN'
            """)
        .param("at", Timestamp.from(at))
        .param("invoiceId", invoiceId)
        .update();
  }

  private static MatchException exception(ResultSet rs, int row) throws SQLException {
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    return new MatchException(
        rs.getObject("id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        ReasonCode.valueOf(rs.getString("reason_code")),
        rs.getString("summary"),
        rs.getBigDecimal("amount_at_issue"),
        ExceptionStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("raised_at").toInstant(),
        resolvedAt == null ? null : resolvedAt.toInstant());
  }
}
