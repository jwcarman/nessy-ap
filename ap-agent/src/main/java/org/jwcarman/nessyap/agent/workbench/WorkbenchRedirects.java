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

/** Where a workbench action sends the person next, and the flash attribute that tells them. */
final class WorkbenchRedirects {

  /** The flash attribute the pages show as a banner. */
  static final String MESSAGE = "message";

  static final String TO_WORKLIST = "redirect:/workbench";

  private WorkbenchRedirects() {}

  static String toCase(UUID exceptionId) {
    return "redirect:/workbench/cases/" + exceptionId;
  }
}
