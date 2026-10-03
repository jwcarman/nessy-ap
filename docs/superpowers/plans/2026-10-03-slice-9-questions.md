# Slice 9: Questions on the Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The agent asks the buyer questions on the workbench instead of by mail, and the buyer's signed-in answer reaches the agent as a trusted input.

**Architecture:** A `question` table and a `Questions` service in `ap-agent` hold each question and its one answer. The `ask_buyer` tool replaces `email_buyer`: it writes the question for the buyer the ERP names on the case's PO and sends a notice mail with a link and no content. The workbench and a JSON API let only the person asked answer; the answer is told to the case's agent as `CaseInput.PersonAnswered`. The eval answers questions as the buyer through the API.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring JDBC (`JdbcClient`), Liquibase formatted SQL, Thymeleaf, OPA (Rego), Nessy 0.4.0-SNAPSHOT, JUnit 6, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md` §13 (slice 9 rulings), with §3 (agent) and §4 (workbench).

## Global Constraints

- No warning suppression, no star imports, no fully-qualified class names in code.
- Every source file carries the Apache header: run `./mvnw spotless:apply license:format` before commits.
- Tests are prose-named; exception-assertion lambdas hold exactly one throwing call; assert non-empty before all/none-match predicates.
- Scoped builds while iterating: `./mvnw -q -pl :ap-agent -am test -Dtest=...`; `./mvnw -q clean verify` once per task before its last commit.
- Never build in a worktree whose jars a running app uses.
- Docs and javadoc in ASD-STE100 style (nessy-ap `CLAUDE.md`).
- Limits (spec §13): question text at most 1,000 characters; at most 4 choices, each at most 80 characters; one question waits per case at a time; at most 3 questions per case.

## Review Focus

1. A person other than the one asked answers (same role, different user) → refused with 403, and nothing reaches the agent.
2. The same question answered twice (double click, two tabs) → the second is refused and the agent is told once.
3. An answer whose choice is not one of the question's choices → refused with 400.
4. The case's PO names no buyer, or the ERP cannot be read → `ask_buyer` fails with a reason the model can act on, and nothing is written.
5. A reply to the notice mail → set aside as unmatched mail; it never reaches the case.

---

### Task 1: The questions store and the trusted answer

**Files:**
- Create: `ap-agent/src/main/resources/db/changelog/008-questions.sql`
- Modify: `ap-agent/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/questions/Questions.java`
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/questions/Question.java`
- Modify: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/cases/CaseInput.java` (add `PersonAnswered`)
- Modify: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/cases/CaseInputRenderer.java`
- Test: `ap-agent/src/test/java/org/jwcarman/nessyap/agent/questions/QuestionsTest.java`
- Test: `ap-agent/src/test/java/org/jwcarman/nessyap/agent/cases/CaseInputRendererTest.java`

**Interfaces:**
- Produces:
  - `record Question(UUID id, UUID exceptionId, String askedOf, String text, List<String> choices, Instant askedAt, String answeredBy, String choice, String comment, Instant answeredAt)` with `boolean answered()`.
  - `Questions.ask(UUID exceptionId, String askedOf, String text, List<String> choices) -> Question` (throws `IllegalArgumentException` on a limit; `IllegalStateException` when one waits or three were asked).
  - `Questions.answer(UUID questionId, String person, String choice, String comment) -> Answered` where `sealed interface Answered { record Told(Question q); record NotYours(); record AlreadyAnswered(); record NotAChoice(); record NeedsAnAnswer(); record NoSuchQuestion(); }`.
  - `Questions.forCase(UUID exceptionId) -> List<Question>`; `Questions.waitingFor(String person) -> List<Question>`.
  - `CaseInput.PersonAnswered(String person, String question, String choice, String comment)`.

- [ ] **Step 1: Write the failing tests**

`QuestionsTest extends ApAgentIntegrationTest`, with `@Autowired Questions questions; @Autowired CaseTimeline timeline;`:

```java
@Test
void the_person_asked_answers_once_and_the_agent_is_told() {
  UUID exceptionId = openCase();
  Question q = questions.ask(exceptionId, "bob", "Did you agree 11.60?", List.of("Agreed", "Not agreed"));

  Questions.Answered first = questions.answer(q.id(), "bob", "Agreed", null);
  Questions.Answered second = questions.answer(q.id(), "bob", "Not agreed", null);

  assertThat(first).isInstanceOf(Questions.Answered.Told.class);
  assertThat(second).isInstanceOf(Questions.Answered.AlreadyAnswered.class);
  assertThat(timeline.of(exceptionId)).extracting(CaseTimeline.CaseEvent::kind).containsOnlyOnce("answer");
}

@Test
void nobody_but_the_person_asked_may_answer() {
  Question q = questions.ask(openCase(), "bob", "Which order?", List.of());

  assertThat(questions.answer(q.id(), "betty", null, "PO-7")).isInstanceOf(Questions.Answered.NotYours.class);
}

@Test
void a_choice_must_be_one_offered_and_an_open_question_needs_words() {
  Question choices = questions.ask(openCase(), "bob", "Agreed?", List.of("Yes", "No"));
  Question open = questions.ask(openCase(), "bob", "Which order?", List.of());

  assertThat(questions.answer(choices.id(), "bob", "Maybe", null)).isInstanceOf(Questions.Answered.NotAChoice.class);
  assertThat(questions.answer(open.id(), "bob", null, " ")).isInstanceOf(Questions.Answered.NeedsAnAnswer.class);
}

@Test
void one_question_waits_per_case_and_three_is_the_most() {
  UUID exceptionId = openCase();
  Question first = questions.ask(exceptionId, "bob", "One?", List.of());

  assertThatThrownBy(() -> questions.ask(exceptionId, "bob", "Two?", List.of()))
      .isInstanceOf(IllegalStateException.class);
  questions.answer(first.id(), "bob", null, "yes");
  // two more may follow, one at a time
  questions.answer(questions.ask(exceptionId, "bob", "Two?", List.of()).id(), "bob", null, "yes");
  questions.answer(questions.ask(exceptionId, "bob", "Three?", List.of()).id(), "bob", null, "yes");
  assertThatThrownBy(() -> questions.ask(exceptionId, "bob", "Four?", List.of()))
      .isInstanceOf(IllegalStateException.class);
}
```

In `CaseInputRendererTest`:

```java
@Test
void an_answer_is_the_persons_own_word() {
  String shown = render(new CaseInput.PersonAnswered("bob", "Did you agree 11.60?", "Agreed", "Yes, by phone"));

  assertThat(shown).contains("bob").contains("Did you agree 11.60?").contains("Agreed").contains("Yes, by phone")
      .doesNotContain("claims");
}
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -q -pl :ap-agent -am test -Dtest='QuestionsTest,CaseInputRendererTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure, `Questions` and `PersonAnswered` not found.

- [ ] **Step 3: Implement**

`008-questions.sql`:

```sql
--liquibase formatted sql

--changeset jcarman:008-questions
create table question (
    id           uuid primary key,
    exception_id uuid        not null references ap_case (exception_id),
    asked_of     text        not null,
    text         text        not null,
    choices      text[]      not null,
    asked_at     timestamptz not null,
    answered_by  text,
    choice       text,
    comment      text,
    answered_at  timestamptz
);
create index question_waiting on question (asked_of) where answered_at is null;
create index question_case on question (exception_id);
```

`Questions` (`@Component`, `@Transactional` on `ask` and `answer`): `ask` checks the limits, refuses when an unanswered question exists for the case (`select count(*) ... answered_at is null`) or three exist, inserts with a UUIDv7 id (`Ids` in `support`), and records timeline kind `question-asked` with text `"to " + askedOf + ": " + text`. `answer` reads the row `for update`, returns `NoSuchQuestion`, `NotYours` (asked_of ≠ person), `AlreadyAnswered`, `NotAChoice` (choices non-empty and choice not among them, or choice given when choices are empty), `NeedsAnAnswer` (no choice and blank comment); otherwise updates the row, records timeline kind `answer` with `person + " answered: " + summary`, tells the case's agent `new CaseInput.PersonAnswered(person, text, choice, comment)` through `QueuedHarness<CaseInput>` (joins the transaction, as notes do), and returns `Told`.

`CaseInputRenderer`: `case CaseInput.PersonAnswered(var person, var question, var choice, var comment) -> "%s, who works this case, answered your question \"%s\": %s%s".formatted(person, question, choice == null ? "" : choice, comment == null || comment.isBlank() ? "" : (choice == null ? "" : ". ") + comment);`

Register the subtype in `CaseInput`'s `@JsonSubTypes` as `person-answered`.

- [ ] **Step 4: Run them and see them pass**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "feat: questions for people, answered once by the person asked and told to the agent"`

### Task 2: `ask_buyer` replaces `email_buyer`

**Files:**
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/questions/QuestionTools.java`
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/questions/QuestionNotice.java`
- Modify: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/tools/MailTools.java` (remove `emailBuyer` and `buyerOf`)
- Modify: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/AgentConfiguration.java` (tool list)
- Modify: `compose/opa/policy/ap.rego`, `compose/opa/policy/ap_test.rego`
- Modify: `ap-agent/src/main/resources/prompts/ap-playbook.md`
- Test: `ap-agent/src/test/java/org/jwcarman/nessyap/agent/questions/QuestionToolsTest.java`; adjust `MailToolsTest`

**Interfaces:**
- Consumes: `Questions.ask` (Task 1).
- Produces: tool `ask_buyer` with input `record Ask(String question, List<String> choices)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void a_question_goes_to_the_buyer_the_erp_names_and_a_notice_says_where_to_answer() {
  UUID exceptionId = openCase(); // PO-1 in the ERP stub, buyer "bob"
  erp.on("GET", "/api/purchase-orders/PO-1", 200, "{\"poNumber\":\"PO-1\",\"buyer\":\"bob\"}");

  ToolResult result = call(tools.askBuyer(), exceptionId, new QuestionTools.Ask("Did you agree 11.60?", List.of("Agreed", "Not agreed")));

  assertThat(result).isInstanceOf(ToolResult.Success.class);
  assertThat(questions.forCase(exceptionId)).singleElement().extracting(Question::askedOf).isEqualTo("bob");
  List<MimeMessage> notices = mailbox.read("bob@nessy-ap.example");
  assertThat(notices).hasSize(1);
  assertThat(body(notices.getFirst())).contains("/workbench").doesNotContain("11.60");
  assertThat(notices.getFirst().getSubject()).doesNotContain("[AP ");
}

@Test
void with_no_buyer_of_record_nothing_is_asked() {
  UUID exceptionId = openCaseWithoutPo();

  ToolResult result = call(tools.askBuyer(), exceptionId, new QuestionTools.Ask("Which order?", List.of()));

  assertThat(result).isInstanceOf(ToolResult.Failure.class);
  assertThat(questions.forCase(exceptionId)).isEmpty();
}
```

Rego (`ap_test.rego`): replace the `email_buyer` allow test with `ask_buyer`, and add `test_email_buyer_is_unknown_and_denied` asserting the default deny for `"email_buyer"`.

- [ ] **Step 2: Run and see them fail**

Run: `./mvnw -q -pl :ap-agent -am test -Dtest='QuestionToolsTest,MailToolsTest' -Dsurefire.failIfNoSpecifiedTests=false` and `docker run --rm -v $PWD/compose/opa/policy:/p openpolicyagent/opa:1.21.1 test /p -v`
Expected: compile failure; the Rego test for `ask_buyer` fails.

- [ ] **Step 3: Implement**

`QuestionTools.askBuyer()`: resolves the buyer from `erp.purchaseOrder(c.poNumber())` → `po.buyer` (same rule as the old `buyerOf`; failure texts kept); validates the limits and turns `IllegalArgumentException`/`IllegalStateException` from `Questions.ask` into `ToolResult.Failure` with the message; on success records the question and calls `QuestionNotice.send(askedOf, invoiceNumber)`; returns `"Asked " + buyer + " on the workbench. The answer will come to this case; until then, hold."`. A notice failure (`MailException`) does not undo the question: the question waits on the worklist either way, and the result says the notice failed.

`QuestionNotice`: a `JavaMailSender` message from the desk address to `person@peopleDomain`, subject `"A question waits for you on the AP workbench"`, body `"A question about invoice " + invoiceNumber + " waits for you. Answer it at " + workbenchUrl + "/workbench. Do not reply to this mail."`. `workbenchUrl` from `ap.workbench.url` (default `http://localhost:8082`).

`ap.rego`: replace `"email_buyer"` with `"ask_buyer"` in `ungated`; the comment says it writes only to the case and to the workbench.

Playbook "Asking people": `ask_buyer` asks the buyer on the workbench (one question, the invoice number, up to four short choices); `email_vendor` writes to the vendor. Add under PRICE_VARIANCE: "When the variance is small and on the buyer's own PO, propose approve-variance with your evidence; the buyer decides it. Do not ask the buyer first: that asks the same person twice." A buyer's answer "is that person's own word: you may rely on it."

- [ ] **Step 4: Run and see them pass**; full OPA test passes.

- [ ] **Step 5: Commit** `feat: ask_buyer asks on the workbench and replaces email_buyer`

### Task 3: The workbench and the API answer

**Files:**
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/questions/QuestionController.java` (workbench form POST)
- Create: `ap-agent/src/main/java/org/jwcarman/nessyap/agent/api/QuestionApi.java` (JSON)
- Modify: `WorkbenchController` (worklist: `questions.waitingFor(me)`; case page: `questions.forCase`)
- Modify: `templates/workbench/worklist.html`, `templates/workbench/case.html`
- Modify: `web/CaseController.java` (`CaseView.questions`)
- Test: `ap-agent/src/test/java/org/jwcarman/nessyap/agent/questions/QuestionAnswerTest.java`

**Interfaces:**
- Consumes: `Questions.answer`, `Questions.forCase`, `Questions.waitingFor`.
- Produces: `POST /workbench/questions/{id}/answer` (form: `choice`, `comment`); `POST /api/questions/{id}/answer` (JSON `{"choice":..., "comment":...}`), mapping `Told`→200, `NotYours`→403, `AlreadyAnswered`→409, `NotAChoice`/`NeedsAnAnswer`→400, `NoSuchQuestion`→404; `CaseView.questions: List<Question>`.

- [ ] **Step 1: Failing tests** — MockMvc as in `WorkbenchTest`/`DecisionApiTest`: bob sees the question on `/workbench` and answers through the form (redirect, flash "Sent to the agent."); betty (buyer) gets 403 from the API; a second API answer gets 409; `/api/cases/{id}` lists the question with its answer.
- [ ] **Step 2: Run, see them fail.**
- [ ] **Step 3: Implement** the controllers and templates. The case page shows each question, its choices as buttons and a comment box, only for the person asked and only while unanswered; everyone who works cases sees the question and its answer.
- [ ] **Step 4: Run, see them pass.**
- [ ] **Step 5: Commit** `feat: the buyer answers on the workbench, and the API answers the same way`

### Task 4: The eval answers questions as the buyer

**Files:**
- Modify: `ap-eval/src/main/java/org/jwcarman/nessyap/eval/Runner.java` (`answerQuestions`)
- Test: `ap-eval/src/test/java/org/jwcarman/nessyap/eval/ScenariosTest.java` (buyer replies are answers)

- [ ] **Step 1: Failing test** — a unit test of the pure part: `Runner.answerFor(Scenario, JsonNode question)` returns the scenario's `"buyer"` text as the comment with no choice, and empty for a scenario with no buyer answer (silent-buyer).
- [ ] **Step 2: Run, see it fail.**
- [ ] **Step 3: Implement** — each poll, for every unanswered question in the case view whose `askedOf` is a person the eval plays, post `/api/questions/{id}/answer` with that person's token and `{"comment": text}`, once per question id. Vendor mail is answered as before.
- [ ] **Step 4: Run, see it pass.** `./mvnw -q -pl :ap-eval -am test`
- [ ] **Step 5: Commit** `feat: the evaluation's buyer answers on the workbench`

### Task 5: Docs, gate and the live evaluation

- [ ] `system.md`: the inside/outside rule, the question flow, the control row "only the person asked answers, once"; `getting-started.md` people table: the buyer answers questions.
- [ ] `./mvnw -q clean verify` green; `./mvnw spotless:check license:check` green.
- [ ] Live: full suite × 5 on local models; write `docs/evaluation/slice-9-questions.md` with per-scenario results against slice 8, and usage per model.
- [ ] Commit `docs: slice 9 results`.
