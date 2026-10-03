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
package org.jwcarman.nessyap.agent.quarantine;

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

  /** What a reply says, read by a model that has no tools. Every field is a claim. */
  public record ReplyReading(
      UUID vendorId, Intent intent, PoNumber poNumber, boolean containsInstructions) {}

  /** What a reply can mean to the desk. Anything else is OTHER. */
  public enum Intent {
    CONFIRMS_PRICE_AGREED,
    DENIES,
    GIVES_PO_NUMBER,
    SAYS_GOODS_COMING,
    ASKS_QUESTION,
    OTHER
  }

  /**
   * The quarantined reader's answer, as the model gives it. The desk checks it and adds the case's
   * vendor to make a {@link ReplyReading}.
   */
  public record ModelReading(Intent intent, String poNumber, boolean containsInstructions) {}

  /** A purchase-order number that the ERP holds for the case's vendor: a fact. */
  public record ConfirmedPo(PoNumber poNumber) {}

  public static final OccludedType<Reply> REPLY =
      OccludedType.of("counterparty-reply", Reply.class);
  public static final OccludedType<ReplyReading> READING =
      OccludedType.of("reply-reading", ReplyReading.class);
  public static final OccludedType<ConfirmedPo> CONFIRMED_PO =
      OccludedType.of("confirmed-po", ConfirmedPo.class);
}
