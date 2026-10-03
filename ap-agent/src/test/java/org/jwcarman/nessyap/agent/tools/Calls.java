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
package org.jwcarman.nessyap.agent.tools;

import java.time.Instant;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessyap.agent.AgentConfiguration;

/** A tool call as the engine would hand it over, for calling a tool directly in a test. */
public record Calls<I>(AgentId agentId, I input, TurnId turn, CallId callId)
    implements ToolCallRequest<I> {

  public static <I> Calls<I> by(AgentId agentId, I input) {
    return new Calls<>(agentId, input, new TurnId(1), new CallId("call-1"));
  }

  @Override
  public AgentType agentType() {
    return AgentConfiguration.AGENT_TYPE;
  }

  @Override
  public ToolName toolName() {
    return new ToolName("under-test");
  }

  @Override
  public Instant deadline() {
    return Instant.now().plusSeconds(30);
  }

  @Override
  public ReplyToken replyToken() {
    return null;
  }
}
