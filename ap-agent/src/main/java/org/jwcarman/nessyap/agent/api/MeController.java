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
package org.jwcarman.nessyap.agent.api;

import java.util.Set;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the API thinks the caller is: handy when a decision is refused for want of a role. */
@RestController
public class MeController {

  public record Me(String username, Set<String> roles) {}

  @GetMapping("/api/me")
  public Me me(Authentication authentication) {
    return new Me(authentication.getName(), RealmRoles.of(authentication));
  }
}
