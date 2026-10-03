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

import java.util.UUID;
import org.jwcarman.nessyap.agent.questions.Answers;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Answers a question as the signed-in person: the same rules as the workbench form. */
@RestController
public class QuestionApi {

  /** A choice, a comment, or both. */
  public record AnswerRequest(String choice, String comment) {}

  public record AnswerResponse(String status, String answeredBy) {}

  private final Answers answers;

  public QuestionApi(Answers answers) {
    this.answers = answers;
  }

  @PostMapping("/api/questions/{questionId}/answer")
  public AnswerResponse answer(
      @PathVariable UUID questionId,
      @RequestBody AnswerRequest request,
      JwtAuthenticationToken me) {
    return switch (answers.answer(questionId, me.getName(), request.choice(), request.comment())) {
      case Answers.Answered.Told _ -> new AnswerResponse("ANSWERED", me.getName());
      case Answers.Answered.NotYours _ ->
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN, "This question waits for someone else");
      case Answers.Answered.AlreadyAnswered _ ->
          throw new ResponseStatusException(HttpStatus.CONFLICT, "This question has its answer");
      case Answers.Answered.NotAChoice _ ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the choices");
      case Answers.Answered.NeedsAnAnswer _ ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Pick a choice or write an answer");
      case Answers.Answered.NoSuchQuestion _ ->
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question");
    };
  }
}
