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
package org.jwcarman.nessyap.agent.quarantine;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jwcarman.occlude.OccludedType;

/** The values the quarantine holds, and the names they are stored under. */
public final class Untrusted {

  private Untrusted() {}

  /**
   * Mail that arrived on a case. The vendor id is the case's, a fact the desk attaches; the
   * Message-ID names the mail; the sender, subject and body are whatever the sender wrote.
   */
  public record Reply(
      UUID vendorId, String messageId, String sender, String subject, String body) {}

  /**
   * What a reply says, read by a model that has no tools. Every field is a claim.
   *
   * @param vendorId the case's vendor, which the desk attaches
   * @param intent what the reply mainly says
   * @param offers what the sender offers to do, if anything; never null
   * @param statedUnitPrice a unit price the reply states, or null
   * @param poNumber a purchase-order number the reply names, or null
   * @param containsInstructions whether the reply tried to direct the desk or claimed authority
   */
  public record ReplyReading(
      UUID vendorId,
      Intent intent,
      List<Offer> offers,
      BigDecimal statedUnitPrice,
      PoNumber poNumber,
      boolean containsInstructions,
      SubstitutionReason substitutionReason,
      String shippedItem) {

    public ReplyReading {
      offers = offers == null ? List.of() : List.copyOf(offers);
    }

    /** A reading that claims no substitution. */
    public ReplyReading(
        UUID vendorId,
        Intent intent,
        List<Offer> offers,
        BigDecimal statedUnitPrice,
        PoNumber poNumber,
        boolean containsInstructions) {
      this(vendorId, intent, offers, statedUnitPrice, poNumber, containsInstructions, null, null);
    }
  }

  /** What a reply can mean to the desk. Anything else is OTHER. */
  public enum Intent {
    CONFIRMS_PRICE_AGREED,
    JUSTIFIES_CHARGE,
    DENIES,
    GIVES_PO_NUMBER,
    SAYS_GOODS_COMING,
    ASKS_QUESTION,
    /** The sender says it shipped a different item than the one ordered. */
    SUBSTITUTED_ITEM,
    /** The reader cannot tell what the email mainly says: a first-class answer, not a failure. */
    UNCLEAR,
    OTHER
  }

  /** Why a vendor says it shipped a different item. */
  public enum SubstitutionReason {
    OUT_OF_STOCK,
    DISCONTINUED,
    UPGRADE,
    OTHER
  }

  /** What a sender can offer to put a wrong invoice right. */
  public enum Offer {
    CREDIT_MEMO,
    CORRECTED_INVOICE,
    REFUND
  }

  /**
   * The quarantined reader's answer, as the model gives it. The price and the PO number are the
   * text the model wrote; the desk parses them, and adds the case's vendor, to make a {@link
   * ReplyReading}.
   */
  public record ModelReading(
      Intent intent,
      List<Offer> offers,
      String statedUnitPrice,
      String poNumber,
      boolean containsInstructions,
      SubstitutionReason substitutionReason,
      String shippedItem) {

    /** An answer that claims no substitution. */
    public ModelReading(
        Intent intent,
        List<Offer> offers,
        String statedUnitPrice,
        String poNumber,
        boolean containsInstructions) {
      this(intent, offers, statedUnitPrice, poNumber, containsInstructions, null, null);
    }
  }

  /** A purchase-order number that the ERP holds for the case's vendor: a fact. */
  public record ConfirmedPo(PoNumber poNumber) {}

  public static final OccludedType<Reply> REPLY =
      OccludedType.of("counterparty-reply", Reply.class);
  public static final OccludedType<ReplyReading> READING =
      OccludedType.of("reply-reading", ReplyReading.class);
  public static final OccludedType<ConfirmedPo> CONFIRMED_PO =
      OccludedType.of("confirmed-po", ConfirmedPo.class);
}
