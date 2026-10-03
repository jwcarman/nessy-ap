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
package org.jwcarman.nessyap.erp.vendor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VendorRepository {

  private final JdbcClient jdbc;

  public VendorRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID id, NewVendor vendor, Instant createdAt) {
    jdbc.sql(
            """
            insert into vendor
                (id, name, payment_terms, contact_name, contact_phone, contact_email, created_at)
            values (:id, :name, :terms, :contactName, :contactPhone, :contactEmail, :createdAt)
            """)
        .param("id", id)
        .param("name", vendor.name())
        .param("terms", vendor.paymentTerms())
        .param("contactName", vendor.contact().name())
        .param("contactPhone", vendor.contact().phone())
        .param("contactEmail", vendor.contact().email())
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }

  public void insertBankAccount(UUID vendorId, BankAccount account) {
    jdbc.sql(
            """
            insert into vendor_bank_account
                (id, vendor_id, account_number, routing_number, status, proposed_at,
                 proposed_by_email)
            values (:id, :vendorId, :accountNumber, :routingNumber, :status, :proposedAt,
                    :proposedByEmail)
            """)
        .param("id", account.id())
        .param("vendorId", vendorId)
        .param("accountNumber", account.accountNumber())
        .param("routingNumber", account.routingNumber())
        .param("status", account.status().name())
        .param("proposedAt", Timestamp.from(account.proposedAt()))
        .param("proposedByEmail", account.proposedByEmail(), Types.VARCHAR)
        .update();
  }

  public Optional<Vendor> find(UUID id) {
    return jdbc.sql("select * from vendor where id = :id")
        .param("id", id)
        .query((rs, row) -> vendor(rs, bankAccounts(id)))
        .optional();
  }

  private List<BankAccount> bankAccounts(UUID vendorId) {
    return jdbc.sql(
            """
            select * from vendor_bank_account
            where vendor_id = :vendorId
            order by proposed_at, id
            """)
        .param("vendorId", vendorId)
        .query(
            (rs, row) ->
                new BankAccount(
                    rs.getObject("id", UUID.class),
                    rs.getString("account_number"),
                    rs.getString("routing_number"),
                    BankAccountStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("proposed_at").toInstant(),
                    rs.getString("proposed_by_email")))
        .list();
  }

  private static Vendor vendor(ResultSet rs, List<BankAccount> accounts) throws SQLException {
    return new Vendor(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("payment_terms"),
        new Contact(
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email")),
        rs.getTimestamp("created_at").toInstant(),
        accounts);
  }
}
