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
package org.jwcarman.nessyap.agent.decisions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The decisions table. */
@Component
public class Decisions {

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public Decisions(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Records a proposal. Asked again about the same call (the engine re-asking after a restart), it
   * keeps the decision and takes the new reply address and deadline.
   */
  public void insert(PendingDecision d) {
    jdbc.sql(
            """
            insert into pending_decision
                (id, agent_id, call_key, reply_token, exception_id, invoice_id, action, amount,
                 rationale, evidence, deadline, status, created_at, required_role, required_user)
            values (:id, :agentId, :callKey, :replyToken, :exceptionId, :invoiceId, :action,
                    :amount, :rationale, :evidence, :deadline, :status, :createdAt, :requiredRole,
                    :requiredUser)
            on conflict (agent_id, call_key) do update
                set reply_token = excluded.reply_token, deadline = excluded.deadline
            """)
        .param("id", d.id())
        .param("agentId", d.agentId().value())
        .param("callKey", d.callKey())
        .param("replyToken", d.replyToken().value())
        .param("exceptionId", d.exceptionId())
        .param("invoiceId", d.invoiceId())
        .param("action", d.action())
        .param("amount", d.amount(), Types.NUMERIC)
        .param("rationale", d.rationale())
        .param("evidence", json.writeValueAsString(d.evidence()))
        .param("deadline", Timestamp.from(d.deadline()))
        .param("status", d.status().name())
        .param("createdAt", Timestamp.from(d.createdAt()))
        .param("requiredRole", d.requiredRole())
        .param("requiredUser", d.requiredUser(), Types.VARCHAR)
        .update();
  }

  public Optional<PendingDecision> find(UUID id) {
    return jdbc.sql("select * from pending_decision where id = :id")
        .param("id", id)
        .query(this::decision)
        .optional();
  }

  /** The decision row, locked until the caller's transaction ends. */
  public Optional<PendingDecision> lock(UUID id) {
    return jdbc.sql("select * from pending_decision where id = :id for update")
        .param("id", id)
        .query(this::decision)
        .optional();
  }

  public Optional<PendingDecision> forCall(AgentId agentId, String callKey) {
    return jdbc.sql(
            "select * from pending_decision where agent_id = :agentId and call_key = :callKey")
        .param("agentId", agentId.value())
        .param("callKey", callKey)
        .query(this::decision)
        .optional();
  }

  public List<PendingDecision> forCase(UUID exceptionId) {
    return jdbc.sql(
            "select * from pending_decision where exception_id = :id order by created_at, id")
        .param("id", exceptionId)
        .query(this::decision)
        .list();
  }

  /**
   * The pending decisions this person may make: their role is the one required, and a buyer's
   * decision is theirs only on their own purchase order.
   */
  public List<PendingDecision> pendingFor(Set<String> roles, String username) {
    if (roles.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            select * from pending_decision
            where status = 'PENDING' and required_role in (:roles)
              and (required_user is null or required_user = :username)
            order by created_at, id
            """)
        .param("roles", roles)
        .param("username", username)
        .query(this::decision)
        .list();
  }

  /** Every pending decision, for a controller, who may decide any of them. */
  public List<PendingDecision> allPending() {
    return jdbc.sql(
            "select * from pending_decision where status = 'PENDING' order by created_at, id")
        .query(this::decision)
        .list();
  }

  public List<UUID> pending() {
    return jdbc.sql("select id from pending_decision where status = 'PENDING' order by created_at")
        .query(UUID.class)
        .list();
  }

  public List<UUID> decidedBefore(Instant cutoff) {
    return jdbc.sql(
            """
            select id from pending_decision
            where status = 'DECIDED' and decided_at <= :cutoff
            order by decided_at
            """)
        .param("cutoff", Timestamp.from(cutoff))
        .query(UUID.class)
        .list();
  }

  public void markDecided(
      UUID id, String decidedBy, boolean approved, String comment, Instant decidedAt) {
    jdbc.sql(
            """
            update pending_decision
            set status = 'DECIDED', decided_by = :decidedBy, approved = :approved,
                decision_comment = :comment, decided_at = :decidedAt
            where id = :id
            """)
        .param("decidedBy", decidedBy)
        .param("approved", approved)
        .param("comment", comment, Types.VARCHAR)
        .param("decidedAt", Timestamp.from(decidedAt))
        .param("id", id)
        .update();
  }

  public void rememberExpectedVersion(UUID id, long version) {
    jdbc.sql("update pending_decision set expected_version = :version where id = :id")
        .param("version", version)
        .param("id", id)
        .update();
  }

  /** The ERP refused before the command could be sent; the answer will say so. */
  public void rememberRefusal(UUID id, String refusal) {
    jdbc.sql("update pending_decision set erp_result = :refusal where id = :id")
        .param("refusal", refusal)
        .param("id", id)
        .update();
  }

  public void markAnswered(UUID id, String erpResult) {
    jdbc.sql("update pending_decision set status = 'ANSWERED', erp_result = :result where id = :id")
        .param("result", erpResult, Types.VARCHAR)
        .param("id", id)
        .update();
  }

  private PendingDecision decision(ResultSet rs, int row) throws SQLException {
    Timestamp decidedAt = rs.getTimestamp("decided_at");
    return new PendingDecision(
        rs.getObject("id", UUID.class),
        new AgentId(rs.getObject("agent_id", UUID.class)),
        rs.getString("call_key"),
        new ReplyToken(rs.getString("reply_token")),
        rs.getObject("exception_id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getString("action"),
        rs.getBigDecimal("amount"),
        rs.getString("rationale"),
        json.readValue(rs.getString("evidence"), STRINGS),
        rs.getTimestamp("deadline").toInstant(),
        DecisionStatus.valueOf(rs.getString("status")),
        rs.getString("decided_by"),
        rs.getObject("approved", Boolean.class),
        rs.getString("decision_comment"),
        decidedAt == null ? null : decidedAt.toInstant(),
        rs.getObject("expected_version", Long.class),
        rs.getString("erp_result"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("required_role"),
        rs.getString("required_user"));
  }
}
