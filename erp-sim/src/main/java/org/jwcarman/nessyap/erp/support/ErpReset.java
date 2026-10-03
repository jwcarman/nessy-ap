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
package org.jwcarman.nessyap.erp.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Empties every table. Used by tests and by the admin reset endpoint. */
@Component
public class ErpReset {

  private final JdbcClient jdbc;

  public ErpReset(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void reset() {
    jdbc.sql(
            """
            truncate table idempotency_record, outbox, erp_audit, match_exception, invoice_line,
                invoice, receipt_line, goods_receipt, po_line, purchase_order,
                vendor_bank_account, vendor
            """)
        .update();
  }
}
