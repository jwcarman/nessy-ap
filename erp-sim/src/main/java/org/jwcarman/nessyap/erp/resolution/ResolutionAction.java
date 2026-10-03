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
package org.jwcarman.nessyap.erp.resolution;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessyap.erp.invoice.InvoiceStatus;

/** What a person may do about an invoice in exception, and where each leaves it. */
public enum ResolutionAction {
  APPROVE_VARIANCE(
      "approve-variance",
      InvoiceStatus.APPROVED,
      true,
      InvoiceStatus.EXCEPTION,
      InvoiceStatus.ON_HOLD),
  SHORT_PAY(
      "short-pay", InvoiceStatus.APPROVED, true, InvoiceStatus.EXCEPTION, InvoiceStatus.ON_HOLD),
  HOLD("hold", InvoiceStatus.ON_HOLD, false, InvoiceStatus.EXCEPTION),
  RELEASE_HOLD("release-hold", InvoiceStatus.EXCEPTION, true, InvoiceStatus.ON_HOLD),
  REJECT("reject", InvoiceStatus.REJECTED, false, InvoiceStatus.EXCEPTION, InvoiceStatus.ON_HOLD),
  REQUEST_CREDIT_MEMO(
      "request-credit-memo",
      InvoiceStatus.ON_HOLD,
      false,
      InvoiceStatus.EXCEPTION,
      InvoiceStatus.ON_HOLD);

  private final String slug;
  private final InvoiceStatus target;
  private final boolean blockedByUnverifiedBankChange;
  private final Set<InvoiceStatus> allowedFrom;

  ResolutionAction(
      String slug,
      InvoiceStatus target,
      boolean blockedByUnverifiedBankChange,
      InvoiceStatus... allowedFrom) {
    this.slug = slug;
    this.target = target;
    this.blockedByUnverifiedBankChange = blockedByUnverifiedBankChange;
    this.allowedFrom = EnumSet.copyOf(List.of(allowedFrom));
  }

  public String slug() {
    return slug;
  }

  public InvoiceStatus target() {
    return target;
  }

  /** True for anything that moves money toward the vendor, or lets it move. */
  public boolean blockedByUnverifiedBankChange() {
    return blockedByUnverifiedBankChange;
  }

  public boolean allowedFrom(InvoiceStatus status) {
    return allowedFrom.contains(status);
  }

  public static Optional<ResolutionAction> fromSlug(String slug) {
    return Arrays.stream(values()).filter(action -> action.slug.equals(slug)).findFirst();
  }
}
