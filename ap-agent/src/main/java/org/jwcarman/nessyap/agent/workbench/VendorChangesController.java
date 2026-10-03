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
package org.jwcarman.nessyap.agent.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;

/**
 * Pending changes to where vendors are paid, and the two steps that verify one: a call to the
 * contact of record, then a second person's confirmation. The ERP enforces both; this page only
 * makes them easy to do right.
 */
@Controller
@RequestMapping("/workbench/vendors")
public class VendorChangesController {

  private static final Set<String> VERIFIERS = Set.of("ap-manager", "controller");
  private static final int RECENT_VENDORS = 50;

  /** One vendor with a change waiting, as the page shows it. */
  public record PendingChange(JsonNode vendor, JsonNode account) {}

  private final Cases cases;
  private final ErpClient erp;

  public VendorChangesController(Cases cases, ErpClient erp) {
    this.cases = cases;
    this.erp = erp;
  }

  @GetMapping
  public String pending(Authentication me, Model model) {
    List<PendingChange> pending = new ArrayList<>();
    for (UUID vendorId : cases.recentVendors(RECENT_VENDORS)) {
      if (erp.vendor(vendorId) instanceof ErpOutcome.Ok<JsonNode>(JsonNode vendor)) {
        for (JsonNode account : vendor.path("bankAccounts")) {
          if ("PENDING_VERIFICATION".equals(account.path("status").asString())) {
            pending.add(new PendingChange(vendor, account));
          }
        }
      }
    }
    model.addAttribute("me", me.getName());
    model.addAttribute("roles", RealmRoles.of(me));
    model.addAttribute("pending", pending);
    model.addAttribute("mayVerify", mayVerify(me));
    return "workbench/vendors";
  }

  @PostMapping("/{vendorId}/bank-changes/{accountId}/call-back")
  public String callBack(
      @PathVariable UUID vendorId,
      @PathVariable UUID accountId,
      @RequestParam String phone,
      @RequestParam(defaultValue = "false") boolean vendorConfirmed,
      Authentication me,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient client,
      RedirectAttributes redirect) {
    requireVerifier(me);
    redirect.addFlashAttribute(
        "message",
        describe(
            erp.recordCallBack(vendorId, accountId, phone, vendorConfirmed, tokenOf(client)),
            vendorConfirmed ? "Call recorded. A second person must confirm." : "Change rejected."));
    return "redirect:/workbench/vendors";
  }

  @PostMapping("/{vendorId}/bank-changes/{accountId}/confirm")
  public String confirm(
      @PathVariable UUID vendorId,
      @PathVariable UUID accountId,
      Authentication me,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient client,
      RedirectAttributes redirect) {
    requireVerifier(me);
    redirect.addFlashAttribute(
        "message",
        describe(
            erp.confirmBankChange(vendorId, accountId, tokenOf(client)),
            "Confirmed: the new account is the one paid."));
    return "redirect:/workbench/vendors";
  }

  private static boolean mayVerify(Authentication me) {
    return RealmRoles.of(me).stream().anyMatch(VERIFIERS::contains);
  }

  private static void requireVerifier(Authentication me) {
    if (!mayVerify(me)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only an AP manager or the controller may verify bank changes");
    }
  }

  private static String tokenOf(OAuth2AuthorizedClient client) {
    return client == null ? null : client.getAccessToken().getTokenValue();
  }

  private static String describe(ErpOutcome<JsonNode> outcome, String done) {
    return switch (outcome) {
      case ErpOutcome.Ok<JsonNode> ok -> done;
      case ErpOutcome.Refused<JsonNode>(int status, String code, String detail) ->
          "The ERP refused: " + detail;
      case ErpOutcome.Unavailable<JsonNode>(String reason) ->
          "The ERP could not be reached; try again.";
    };
  }
}
