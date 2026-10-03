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
package org.jwcarman.nessyap.erp.po;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PurchaseOrderRepository {

  private final JdbcClient jdbc;

  public PurchaseOrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID id, NewPurchaseOrder order, Instant createdAt) {
    jdbc.sql(
            """
            insert into purchase_order (id, po_number, vendor_id, buyer, created_at)
            values (:id, :poNumber, :vendorId, :buyer, :createdAt)
            """)
        .param("id", id)
        .param("poNumber", order.poNumber())
        .param("vendorId", order.vendorId())
        .param("buyer", order.buyer())
        .param("createdAt", Timestamp.from(createdAt))
        .update();
    for (PoLine line : order.lines()) {
      jdbc.sql(
              """
              insert into po_line (id, po_id, line_no, item, quantity, unit_price)
              values (:id, :poId, :lineNo, :item, :quantity, :unitPrice)
              """)
          .param("id", Ids.next())
          .param("poId", id)
          .param("lineNo", line.lineNo())
          .param("item", line.item())
          .param("quantity", line.quantity())
          .param("unitPrice", line.unitPrice())
          .update();
    }
  }

  public Optional<PurchaseOrder> findByNumber(String poNumber) {
    return jdbc.sql("select * from purchase_order where po_number = :poNumber")
        .param("poNumber", poNumber)
        .query(
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new PurchaseOrder(
                  id,
                  rs.getString("po_number"),
                  rs.getObject("vendor_id", UUID.class),
                  rs.getString("buyer"),
                  rs.getTimestamp("created_at").toInstant(),
                  lines(id));
            })
        .optional();
  }

  private List<PoLine> lines(UUID poId) {
    return jdbc.sql("select * from po_line where po_id = :poId order by line_no")
        .param("poId", poId)
        .query(
            (rs, row) ->
                new PoLine(
                    rs.getInt("line_no"),
                    rs.getString("item"),
                    rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("unit_price")))
        .list();
  }
}
