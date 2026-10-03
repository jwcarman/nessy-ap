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
package org.jwcarman.nessyap.erp.po;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReceiptRepository {

  private final JdbcClient jdbc;

  public ReceiptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(GoodsReceipt receipt) {
    jdbc.sql(
            """
            insert into goods_receipt (id, po_id, received_at)
            values (:id, :poId, :receivedAt)
            """)
        .param("id", receipt.id())
        .param("poId", receipt.poId())
        .param("receivedAt", Timestamp.from(receipt.receivedAt()))
        .update();
    for (ReceiptLine line : receipt.lines()) {
      jdbc.sql(
              """
              insert into receipt_line (id, receipt_id, po_line_no, quantity)
              values (:id, :receiptId, :poLineNo, :quantity)
              """)
          .param("id", Ids.next())
          .param("receiptId", receipt.id())
          .param("poLineNo", line.poLineNo())
          .param("quantity", line.quantity())
          .update();
    }
  }

  public List<GoodsReceipt> findByPo(UUID poId) {
    return jdbc.sql("select * from goods_receipt where po_id = :poId order by received_at, id")
        .param("poId", poId)
        .query(
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new GoodsReceipt(
                  id, poId, rs.getTimestamp("received_at").toInstant(), lines(id));
            })
        .list();
  }

  private List<ReceiptLine> lines(UUID receiptId) {
    return jdbc.sql("select * from receipt_line where receipt_id = :receiptId order by po_line_no")
        .param("receiptId", receiptId)
        .query((rs, row) -> new ReceiptLine(rs.getInt("po_line_no"), rs.getBigDecimal("quantity")))
        .list();
  }
}
