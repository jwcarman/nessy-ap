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
package org.jwcarman.nessyap.agent.cases;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.NameBasedGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The case index: which agent works which exception, and which open cases a purchase order touches.
 * Nessy finds agents by id only, so the business keys live here.
 */
@Component
public class Cases {

  /** Fixed forever: changing it would give every existing case a new agent. */
  private static final UUID NAMESPACE = UUID.fromString("6f1b2c64-4c0e-5d7a-9a52-2a9e3c7d0b11");

  private static final NameBasedGenerator AGENT_IDS = Generators.nameBasedGenerator(NAMESPACE);

  private final JdbcClient jdbc;
  private final Clock clock;

  public Cases(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** The agent for an exception: the same id every time, so redelivery finds the same agent. */
  public AgentId agentFor(UUID exceptionId) {
    return new AgentId(AGENT_IDS.generate(exceptionId.toString()));
  }

  /** Opens a case for a raised exception. Opening one that is already open changes nothing. */
  public void open(MatchExceptionRaised raised) {
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.sql(
            """
            insert into ap_case
                (exception_id, agent_id, invoice_id, invoice_number, vendor_id, po_number,
                 reason_code, amount, status, opened_at, updated_at)
            values (:exceptionId, :agentId, :invoiceId, :invoiceNumber, :vendorId, :poNumber,
                    :reasonCode, :amount, 'INVESTIGATING', :now, :now)
            on conflict (exception_id) do nothing
            """)
        .param("exceptionId", raised.exceptionId())
        .param("agentId", agentFor(raised.exceptionId()).value())
        .param("invoiceId", raised.invoiceId())
        .param("invoiceNumber", raised.invoiceNumber())
        .param("vendorId", raised.vendorId())
        .param("poNumber", raised.poNumber(), Types.VARCHAR)
        .param("reasonCode", raised.reasonCode().name())
        .param("amount", raised.amountAtIssue())
        .param("now", now)
        .update();
    addAgent(raised.exceptionId(), AgentConfiguration.AGENT_TYPE, agentFor(raised.exceptionId()));
  }

  /** Records an agent that worked the case. Recording one twice changes nothing. */
  public void addAgent(UUID exceptionId, AgentType type, AgentId agentId) {
    jdbc.sql(
            """
            insert into case_agent (exception_id, agent_type, agent_id)
            values (:case, :type, :agent)
            on conflict do nothing
            """)
        .param("case", exceptionId)
        .param("type", type.value())
        .param("agent", agentId.value())
        .update();
  }

  /** Every agent that worked the case, by type and id. */
  public List<Map.Entry<AgentType, AgentId>> agents(UUID exceptionId) {
    return jdbc.sql("select agent_type, agent_id from case_agent where exception_id = :case")
        .param("case", exceptionId)
        .query(
            (rs, row) ->
                Map.entry(
                    new AgentType(rs.getString("agent_type")),
                    new AgentId(rs.getObject("agent_id", UUID.class))))
        .list();
  }

  public List<AgentId> openCasesForPo(String poNumber) {
    return jdbc.sql(
            "select agent_id from ap_case where po_number = :poNumber and status <> 'RESOLVED'")
        .param("poNumber", poNumber)
        .query((rs, row) -> new AgentId(rs.getObject("agent_id", UUID.class)))
        .list();
  }

  public Optional<CaseRecord> find(UUID exceptionId) {
    return jdbc.sql("select * from ap_case where exception_id = :id")
        .param("id", exceptionId)
        .query(Cases::record)
        .optional();
  }

  public Optional<CaseRecord> forAgent(AgentId agentId) {
    return jdbc.sql("select * from ap_case where agent_id = :id")
        .param("id", agentId.value())
        .query(Cases::record)
        .optional();
  }

  /** The most recent cases, newest first. */
  public List<CaseRecord> recent(int limit) {
    return jdbc.sql("select * from ap_case order by opened_at desc limit :limit")
        .param("limit", limit)
        .query(Cases::record)
        .list();
  }

  /** The vendors of recent cases: whose bank changes the AP team is likely to be asked about. */
  public List<UUID> recentVendors(int limit) {
    return jdbc.sql(
            """
            select vendor_id from ap_case group by vendor_id
            order by max(opened_at) desc limit :limit
            """)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * What the case's agent has read, as a label that only rises: once its agent has read a claim
   * that nothing trusted endorsed, every proposal after it is influenced by that claim.
   */
  public record Integrity(boolean influencedByUnendorsed, boolean instructionsSeen) {}

  /** The agent read an unendorsed claim; it tried to give instructions if {@code instructions}. */
  public void markReadUnendorsed(UUID exceptionId, boolean instructions) {
    jdbc.sql(
            """
            update ap_case set influenced_by_unendorsed = true,
                               instructions_seen = instructions_seen or :instructions
            where exception_id = :id
            """)
        .param("instructions", instructions)
        .param("id", exceptionId)
        .update();
  }

  public Integrity integrity(UUID exceptionId) {
    return jdbc.sql(
            "select influenced_by_unendorsed, instructions_seen from ap_case where exception_id = :id")
        .param("id", exceptionId)
        .query(
            (rs, row) ->
                new Integrity(
                    rs.getBoolean("influenced_by_unendorsed"), rs.getBoolean("instructions_seen")))
        .optional()
        .orElse(new Integrity(false, false));
  }

  public void setStatus(UUID exceptionId, CaseStatus status) {
    jdbc.sql("update ap_case set status = :status, updated_at = :now where exception_id = :id")
        .param("status", status.name())
        .param("now", Timestamp.from(clock.instant()))
        .param("id", exceptionId)
        .update();
  }

  private static CaseRecord record(ResultSet rs, int row) throws SQLException {
    return new CaseRecord(
        rs.getObject("exception_id", UUID.class),
        new AgentId(rs.getObject("agent_id", UUID.class)),
        rs.getObject("invoice_id", UUID.class),
        rs.getString("invoice_number"),
        rs.getObject("vendor_id", UUID.class),
        rs.getString("po_number"),
        ReasonCode.valueOf(rs.getString("reason_code")),
        rs.getBigDecimal("amount"),
        CaseStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
