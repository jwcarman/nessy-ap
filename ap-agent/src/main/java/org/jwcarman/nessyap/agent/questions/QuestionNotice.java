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
package org.jwcarman.nessyap.agent.questions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Tells a person that a question waits for them on the workbench. The notice carries no question
 * and no case token: there is nothing in it to answer or to forge, and a reply to it is set aside
 * as mail that answers no case.
 */
@Component
public class QuestionNotice {

  private final JavaMailSender mail;
  private final String deskAddress;
  private final String peopleDomain;
  private final String workbenchUrl;

  public QuestionNotice(
      JavaMailSender mail,
      @Value("${ap.mail.desk-address}") String deskAddress,
      @Value("${ap.mail.people-domain}") String peopleDomain,
      @Value("${ap.workbench.url}") String workbenchUrl) {
    this.mail = mail;
    this.deskAddress = deskAddress;
    this.peopleDomain = peopleDomain;
    this.workbenchUrl = workbenchUrl;
  }

  /** Sends the notice. A failure is the caller's to report; the question waits either way. */
  public void send(String person, String invoiceNumber) {
    SimpleMailMessage notice = new SimpleMailMessage();
    notice.setFrom(deskAddress);
    notice.setTo(person + "@" + peopleDomain);
    notice.setSubject("A question waits for you on the AP workbench");
    notice.setText(
        "A question about invoice "
            + invoiceNumber
            + " waits for you. Answer it at "
            + workbenchUrl
            + "/workbench\n\nDo not reply to this mail: a reply does not reach the case.");
    mail.send(notice);
  }
}
