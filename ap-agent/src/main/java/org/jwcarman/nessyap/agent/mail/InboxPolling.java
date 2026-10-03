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
package org.jwcarman.nessyap.agent.mail;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reads the desk's inbox on a schedule; off in tests, which poll by hand. */
@Component
@ConditionalOnProperty(name = "ap.mail.poll.enabled", havingValue = "true")
public class InboxPolling {

  private static final Logger log = LoggerFactory.getLogger(InboxPolling.class);

  private final InboxPoller poller;

  public InboxPolling(InboxPoller poller) {
    this.poller = poller;
  }

  @Scheduled(fixedDelayString = "${ap.mail.poll.interval}")
  public void poll() {
    try {
      poller.pollOnce();
    } catch (MessagingException e) {
      log.warn("The desk's inbox could not be read; trying again shortly: {}", e.getMessage());
    }
  }
}
