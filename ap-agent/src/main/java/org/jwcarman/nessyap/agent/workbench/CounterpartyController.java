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
package org.jwcarman.nessyap.agent.workbench;

import java.util.UUID;
import org.jwcarman.nessyap.agent.mail.Counterparty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Development only: answer the desk's mail as the vendor or buyer it went to. The answer is sent as
 * real mail, so it reaches the case the way a real reply would.
 */
@Controller
@RequestMapping("/workbench/counterparty")
@ConditionalOnProperty(name = "ap.counterparty.enabled", havingValue = "true")
public class CounterpartyController {

  private final Counterparty counterparty;

  public CounterpartyController(Counterparty counterparty) {
    this.counterparty = counterparty;
  }

  @GetMapping
  public String page(Model model) {
    model.addAttribute("sent", counterparty.recent(50));
    return "workbench/counterparty";
  }

  @PostMapping("/{id}/reply")
  public String reply(
      @PathVariable UUID id, @RequestParam String text, RedirectAttributes redirect) {
    Counterparty.Sent original =
        counterparty
            .find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such mail"));
    if (!text.isBlank()) {
      counterparty.reply(original, text);
      redirect.addFlashAttribute("message", "Replied as " + original.recipient() + ".");
    }
    return "redirect:/workbench/counterparty";
  }
}
