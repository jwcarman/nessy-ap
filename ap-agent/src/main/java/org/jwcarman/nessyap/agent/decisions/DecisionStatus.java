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
package org.jwcarman.nessyap.agent.decisions;

/**
 * Where a proposal stands. PENDING waits on a decider; DECIDED is decided but not yet carried
 * through (the ERP was unreachable, or the process stopped); ANSWERED is done, one way or the
 * other.
 */
public enum DecisionStatus {
  PENDING,
  DECIDED,
  ANSWERED
}
