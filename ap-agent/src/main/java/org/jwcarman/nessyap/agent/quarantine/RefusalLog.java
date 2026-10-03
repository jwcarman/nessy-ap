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

import org.jwcarman.occlude.RefusalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes every Occlude refusal to the log an operator reads. Occlude keeps each refusal in its
 * record, and the record is the evidence; this line is the alarm. The event names the gate, the
 * reason and the value's id, and never the value, so the log carries no mail.
 *
 * <p>A plain {@link EventListener}, not a transactional one: a refusal inside a transaction that
 * rolls back is still in Occlude's record, so it must still reach the log.
 */
@Component
public class RefusalLog {

  private static final Logger log = LoggerFactory.getLogger(RefusalLog.class);

  @EventListener
  public void on(RefusalEvent refusal) {
    log.warn(
        "Occlude refused {} at {} ({}), value {}",
        refusal.operation(),
        refusal.portal(),
        refusal.reason(),
        refusal.valueId());
  }
}
