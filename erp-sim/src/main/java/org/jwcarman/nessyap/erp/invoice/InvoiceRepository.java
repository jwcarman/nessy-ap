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
package org.jwcarman.nessyap.erp.invoice;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessyap.erp.matching.PriorInvoice;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

  private final JdbcClient jdbc;

  public InvoiceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Invoice invoice) {
    jdbc.sql(
            """
            insert into invoice
                (id, vendor_id, invoice_number, po_number, invoice_date, tax, freight, total,
                 approved_amount, status, version, received_at)
            values (:id, :vendorId, :number, :poNumber, :invoiceDate, :tax, :freight, :total,
                    :approved, :status, :version, :receivedAt)
            """)
        .param("id", invoice.id())
        .param("vendorId", invoice.vendorId())
        .param("number", invoice.invoiceNumber())
        .param("poNumber", invoice.poNumber(), Types.VARCHAR)
        .param("invoiceDate", invoice.invoiceDate())
        .param("tax", invoice.tax())
        .param("freight", invoice.freight())
        .param("total", invoice.total())
        .param("approved", invoice.approvedAmount(), Types.NUMERIC)
        .param("status", invoice.status().name())
        .param("version", invoice.version())
        .param("receivedAt", Timestamp.from(invoice.receivedAt()))
        .update();
    for (InvoiceLine line : invoice.lines()) {
      jdbc.sql(
              """
              insert into invoice_line
                  (id, invoice_id, line_no, po_line_no, description, quantity, unit_price)
              values (:id, :invoiceId, :lineNo, :poLineNo, :description, :quantity, :unitPrice)
              """)
          .param("id", Ids.next())
          .param("invoiceId", invoice.id())
          .param("lineNo", line.lineNo())
          .param("poLineNo", line.poLineNo(), Types.INTEGER)
          .param("description", line.description())
          .param("quantity", line.quantity())
          .param("unitPrice", line.unitPrice())
          .update();
    }
  }

  public Optional<Invoice> find(UUID id) {
    return jdbc.sql("select * from invoice where id = :id")
        .param("id", id)
        .query(InvoiceRepository::header)
        .optional()
        .map(invoice -> invoice.withLines(lines(id)));
  }

  public List<Invoice> findByVendor(UUID vendorId) {
    return jdbc
        .sql("select * from invoice where vendor_id = :vendorId order by received_at desc, id desc")
        .param("vendorId", vendorId)
        .query(InvoiceRepository::header)
        .list()
        .stream()
        .map(invoice -> invoice.withLines(lines(invoice.id())))
        .toList();
  }

  public List<PriorInvoice> priorInvoices(UUID vendorId, UUID excluding) {
    return jdbc.sql(
            """
            select id, invoice_number, po_number, invoice_date, total from invoice
            where vendor_id = :vendorId and id <> :excluding and status <> 'REJECTED'
            """)
        .param("vendorId", vendorId)
        .param("excluding", excluding)
        .query(
            (rs, row) ->
                new PriorInvoice(
                    rs.getObject("id", UUID.class),
                    rs.getString("invoice_number"),
                    rs.getString("po_number"),
                    rs.getObject("invoice_date", LocalDate.class),
                    rs.getBigDecimal("total")))
        .list();
  }

  /**
   * Moves an invoice to a new status if, and only if, it is still at the version the caller read.
   *
   * @return false when the version had moved on, so nothing changed
   */
  public boolean updateStatus(
      UUID id, InvoiceStatus status, BigDecimal approvedAmount, long expectedVersion) {
    return jdbc.sql(
                """
                update invoice
                set status = :status, approved_amount = :approved, version = version + 1
                where id = :id and version = :expected
                """)
            .param("status", status.name())
            .param("approved", approvedAmount, Types.NUMERIC)
            .param("id", id)
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  private List<InvoiceLine> lines(UUID invoiceId) {
    return jdbc.sql("select * from invoice_line where invoice_id = :invoiceId order by line_no")
        .param("invoiceId", invoiceId)
        .query(
            (rs, row) ->
                new InvoiceLine(
                    rs.getInt("line_no"),
                    rs.getObject("po_line_no", Integer.class),
                    rs.getString("description"),
                    rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("unit_price")))
        .list();
  }

  private static Invoice header(ResultSet rs, int row) throws SQLException {
    return new Invoice(
        rs.getObject("id", UUID.class),
        rs.getObject("vendor_id", UUID.class),
        rs.getString("invoice_number"),
        rs.getString("po_number"),
        rs.getObject("invoice_date", LocalDate.class),
        rs.getBigDecimal("tax"),
        rs.getBigDecimal("freight"),
        rs.getBigDecimal("total"),
        rs.getBigDecimal("approved_amount"),
        InvoiceStatus.valueOf(rs.getString("status")),
        rs.getLong("version"),
        rs.getTimestamp("received_at").toInstant(),
        List.of());
  }
}
