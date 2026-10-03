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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReceiptPosted;

/**
 * Everything that can happen to a case, told to its agent. Nessy keeps waiting inputs in the
 * agent's backlog, so each one names its kind in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CaseInput.ExceptionRaised.class, name = "exception-raised"),
  @JsonSubTypes.Type(value = CaseInput.ReceiptArrived.class, name = "receipt-arrived"),
  @JsonSubTypes.Type(value = CaseInput.PersonNote.class, name = "person-note"),
  @JsonSubTypes.Type(value = CaseInput.CounterpartyReply.class, name = "counterparty-reply"),
  @JsonSubTypes.Type(value = CaseInput.DecisionApplied.class, name = "decision-applied")
})
public sealed interface CaseInput {

  /** The ERP raised the exception this case is about. Always the first input. */
  record ExceptionRaised(MatchExceptionRaised event) implements CaseInput {}

  /** Goods arrived against the case's purchase order. */
  record ReceiptArrived(ReceiptPosted event) implements CaseInput {}

  /** A person working the case wrote something to the agent. */
  record PersonNote(String author, String text) implements CaseInput {}

  /** A vendor or buyer answered something the agent asked. */
  record CounterpartyReply(String from, String text) implements CaseInput {}

  /** A decision reached the ERP after the agent had stopped waiting for it. */
  record DecisionApplied(UUID decisionId, String action, String outcome) implements CaseInput {}
}
