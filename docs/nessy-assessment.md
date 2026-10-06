# Assessing Nessy for this use case

Nessy AP is the first enterprise application built on Nessy. This page is a critique: how easy
Nessy was to use, how far it reached into the application, how much code it needed, and where it
failed us. The measurements are from the `main` branch on 2026-10-06, which builds against the
released Nessy 0.5.0. Each finding was checked against Nessy's source and against that release.

## The verdict

Nessy fits case-based work, where one agent works one case for days and people decide. The
queued door, the transactional `tell` and the policy approver do real work that we did not have to
write. The desk uses only Nessy's public API: the agent's story, its status and its usage are all
public reads.

The gaps are in two areas:
- the approval life cycle after a person decides (F1, F4, F9);
- trust in the input (F11, F13).

Each gap costs us application code. Of the fifteen findings, eight are fixed or answered and one
(F4) is partly fixed. Among them are the dispatcher stall (F8), which was a release blocker, and the read API for an agent's story
(F3), which was the reason the desk once imported an engine type.

Since the desk's rules came first ([Stay deterministic as long as you can](deterministic-first.md)),
the agent settles about a third of the cases, the ones that need judgment. Nessy's work is
concentrated where it earns its keep.

## How much code, and whose

| Module | Code lines | Nessy in it |
|---|---|---|
| `erp-sim` (the ERP simulator) | 2,624 | none |
| `ap-agent` (the desk) | 5,572 | 31 of 91 files |
| `ap-eval` (the evaluation) | 1,744 | none |
| `ap-contracts` (shared events) | 55 | none |

"Code lines" excludes blank lines, comments, imports and package lines.

Inside `ap-agent`, the code divides like this, by package:

| Package | Lines | Files that use Nessy | What it is |
|---|---|---|---|
| `decisions` | 818 | 9 | A person decides, and the decision runs as that person in the ERP. The approver desk, the facts for the policy, the evidence check. About 100 lines exist only because of Nessy gaps (F1, F4). |
| `cases` | 656 | 6 | The case index, the timeline, the input renderer, the case's usage and turns. |
| `resolver` | 649 | 1 | The DMN rules. Nessy is used only to propose through the same approver. |
| `mail` | 546 | 1 | The Camel mail route. |
| `tools` | 481 | 3 | Mostly application logic: ERP reads, mail rules, limits. About a fifth is the `Tool` interface around it. |
| `workbench` | 427 | 0 | The pages people decide on. |
| `quarantine` | 404 | 2 | The quarantined reader, a direct harness. |
| `questions` | 359 | 2 | Questions to people, and their answers. |
| `oversight` | 277 | 3 | The pause, the budget and the metrics. |
| everything else | 955 | 4 | The ERP client, the event listener, the APIs, security. |

The application imports 42 Nessy types. All of them come from public packages: `api` (for example
`QueuedHarness`, `Tool`, `ApprovalRequest`, `Replies`, `AgentStories` and `AgentWork`), and the
`PolicyApprover` and `OpaPolicyEngine` of the approval modules. None comes from the engine.

**Most of `ap-agent` would exist with any agent framework.** The part that is Nessy's is small.
The part that Nessy made us write is smaller, but it is in the places that matter most: decisions
and audit.

## How invasive it is

- **Nessy stays in one module.** The ERP, the evaluation, the Camel route, Keycloak and OPA know
  nothing about it. The domain has no Nessy types.
- **The desk uses only the public API.** The audit trail, the evidence check, the case view's
  "is the agent busy?" and the agent budget read the agent's story and status through
  `AgentStories` and `AgentWork`.
- **Nessy shares the application's database and its transactions.** This is a strength: a
  RabbitMQ message, the case index and the `tell` to the agent commit together. It is also a
  coupling: Nessy's tables live in the application's schema, and an engine upgrade is a schema
  change.
- **Nessy needs no secret of its own.** A late decision is answered by the agent and the call's
  idempotency key, which the desk already stores. The endpoint that answers is guarded by the
  desk's own security.
- **Nessy is a released dependency.** Nessy AP builds against Nessy 0.5.0 from Maven Central.

## What was easy

- **One agent for each case.** The queued door with a name-based `AgentId` matches case
  management. An agent waits for days with an empty backlog and costs nothing.
- **Exactly-once intake.** `tell` joins the caller's transaction. The event listener records the
  event id and tells the agent in one transaction, with no outbox of our own.
- **People in the loop.** `PolicyApprover` with OPA's `delegate` routes each proposal to a role,
  and `Replies` answers it days later, by the agent and the call's idempotency key. The approval
  records who decided. We did not invent an approval engine.
- **Approvals with no agent behind them.** The desk's rules propose through the same
  `PolicyApprover`, the same enricher and the same workbench, under an agent type of their own, and
  no agent waits on their answer. Nessy's approval stack needed no change for a proposer that
  is not a model.
- **What an agent is doing.** `AgentWork.status` says whether an agent is idle, working, waiting
  on a person or terminated. It counts an input that is told but not yet started as work, which a
  check of the last turn missed.
- **How a turn ended.** Each turn ends in one event: `Answered`, `TurnFailed`, `TurnStopped` or
  `TurnRefused`. The desk tells a person which one, with its reason.
- **Any model.** The agent ran on a local model through LM Studio with no change to the
  application. Tool schemas come from Java records.
- **A one-shot is a direct harness.** The quarantined reader is a `DirectHarness` with no tools
  and a typed answer. Value types such as `PoNumber` reach the model as plain strings.
- **Cost per case.** `UsageReports` gives any agent's usage by model, projected from its stored
  events, so the desk prices each case from every agent that worked it, after any restart.
- **Measured from the start.** Nessy publishes usage as OpenTelemetry-style metrics, with input,
  output, cache and reasoning counts for each model.
- **Agent tests without mocks.** A scripted inference provider drives the real engine, so the
  tests cover the real queued door, approvals and backlog.

## How it helped the evaluation

An agentic system is only as trustworthy as its evaluation, so this matters as much as the
runtime. Most of what the [evaluation](evaluation/index.md) scores, it can score because Nessy
records it.

- **Every inference is on the record, with its usage.** The stored events carry the usage of
  each model call: answers, tool requests, refusals, failures and retried attempts. The
  `UsageReports` projection over them gives each case's cost per model from every agent that
  worked it. Because the cost comes from the record, not from a global counter, cases can run
  side by side, and a restart loses nothing.
- **What the agent read is on the record.** The evidence check asks whether the agent read each
  id it cites. The desk answers that from the agent's story: every result its tools returned,
  read through `AgentStories`. A framework that kept only the final answer could not support this
  check.
- **Decisions are records, not callbacks.** Each proposal is an approval request with an action,
  a rationale, evidence and the role the policy chose. Scoring "correct", "routed" and "evidence"
  is reading those records.
- **Agents have stable identities.** A case's agent is named from the exception id, and a
  reader's from the reply's Message-ID. The evaluation and the desk find every agent of a case
  without a lookup table in memory.
- **The evaluation knows when a case is finished.** A case is finished when no agent on it is
  working. `AgentWork` answers that, and waiting on a person does not count as working.
- **Narration made the tests deterministic.** Integration tests wait for a turn's ending instead
  of sleeping. A scripted model drives the real engine, so the tests cover the real queued door,
  approvals and the backlog.
- **The queued door's guarantees are testable as scenarios.** Exactly-once intake made the
  `redelivered` scenario meaningful: a repeated event must not start the case over, and 20 of
  20 runs proved it.

Where Nessy makes the evaluation harder:
- **A turn summary is a fold the application writes.** The audit trail lists each turn: what
  started it, how many times it asked for tools, and how it ended. The budget counts the turns.
  The desk writes both as folds over the story (`AgentTurns`, 105 lines), and each fold reads the
  whole story, because there is no read from the end. Every application with an audit view will
  write this again. The request is with Nessy.
- **No published test kit (F5).** Every application writes its own scripted model.

## Where it fought us

| Finding | What it cost us |
|---|---|
| **F1.** A tool cannot see its own approval. | A table of decisions, keyed by the call's idempotency key, so that `propose_resolution` can find the decision that let it run. Answered by the key: the table is ours, and the key joins it to the call. |
| **F3.** No read API for an agent's story. | Until it was fixed, the audit trail, the evidence check and the case view's turn state used an internal engine type. Fixed: `AgentStories` and `AgentWork`. What is left is the turn fold above. |
| **F4.** A late decision has no channel. | A separate path that tells the agent when the ERP carried out a decision after its call stopped waiting. Partly fixed: a late answer is `Ignored`, by key, with no secret to keep. |
| **F5.** No scripted model for tests. | About 90 lines of test support that every Nessy application will write again. |
| **F6.** Narration cannot be joined to a tool call. | The application writes its own timeline for people to read. Fixed in 0.4.0: `ActionsRequested` carries each call. |
| **F7.** A call key is unique only within one agent. | A composite key in our own table. A silent collision if we had not read the code. Fixed in 0.4.0: an `IdempotencyKey` for each call, shared by its approval and its run. |
| **F8.** The queued dispatcher could stop for good. | Found under load in the first live run. Every later case was told but never ran. Fixed in 0.4.0. |
| **F9.** A person cannot reach an agent while its proposal waits. | A note from the workbench waits until the decision is made, which is the wrong order for AP. |
| **F10.** `Turn.tokens` was always 0. | The evaluation reads the metric before and after each case, which works only while cases run one at a time. Fixed in 0.4.0: the field is removed, and usage is read through `UsageReports`. |
| **F11.** Inputs carry no provenance. | The application frames untrusted text itself. A prompt injection still persuaded the agent in 4 of 5 runs. |
| **F12.** Nothing checks that the policy knows a gated tool. | A new tool met an old policy, and a fraudulent vendor received mail. The policy now denies any tool it does not name. |
| **F13.** Stored history has no retention or cleanup. | The quarantined reader's history holds the text of every reply, outside Occlude. Nessy's storage codec encrypts it (the desk uses codec-crypto), but nothing expires it, and Occlude's erasure cannot reach it. |
| **F14.** The direct door fails inside a caller's transaction, and nothing anticipated it. | We called it inside the mail route's transaction (our mistake: a model call held a transaction open). Every live read failed with an internal error, and no test saw it. The reader now suspends the transaction. Fixed in 0.4.0: the direct door refuses, with a clear error. |
| **F15.** A dropped connection to the model ends the turn. | The OpenAI adapter reported a transport failure as "unknown", and the engine retried only "transient" failures. Under parallel load on LM Studio, each dropped request left a case with nobody acting on it. Fixed in 0.4.0. |

Two smaller points:
- **The `Tool` interface is verbose for simple tools.** Each tool implements four methods.
  `ErpTools` hides this behind a helper, but a small builder in Nessy would remove it.
- **Judgment is the model's part, and Nessy cannot fix it.** On the small local model, failures
  were judgment (a missing PO, a persuasive reply). On the larger hosted models, the failures were
  the desk's own (a playbook rule, the scorer) and copied ids. Nessy's job is to make the
  deterministic controls easy to add around the model, and the policy approver does that well:
  the gate on ungrounded citations was one enricher fact and one policy rule.

## What the desk reads from Nessy's record

Nessy records more than an application must read. The desk reads these parts as well:
- **Why a turn ended.** `Answered` says when the model was cut off at its output limit, and
  `TurnFailed` carries the kind of failure. The case's timeline gives a person both.
- **Model calls, as metrics.** Nessy's meter for each model call is tagged with its finish reason
  and its failure type, so the desk's alerts read it and count nothing again.
- **The approvals that wait on people.** `AgentWork.waitingApprovals()` lists them. Every minute
  the desk checks its proposals against that list and reports any difference (`ApprovalDrift`).
- **The facts an approver was shown.** `StoryContent.approvalFacts` reads them back for each
  call, and the audit trail shows them beside each decision.
- **The inputs that wait.** The case view counts the inputs queued for the case's agent.

## What would make Nessy a better fit

In order of value to this application:
1. **Make approvals complete:** a late decision that was carried out has a channel to the agent
   (F4), and a person can reach an agent while its proposal waits (F9).
2. **Provenance on input (F11).** Let an application say how far each input is trusted, so that
   the renderer, the policy and the trail can use it. The Occlude experiment in this repository
   is a candidate design.
3. **Ready-made turn summaries, and a read from the end of the story.** Audit and cost are the
   first questions an enterprise asks. Today each application folds the whole story to answer
   them.
4. **A published test kit (F5)** with a scripted model and a narration tap.
5. **Retention for stored history (F13).**
