# Assessing Nessy for this use case

Nessy AP is the first enterprise application built on Nessy. This page is a critique: how easy
Nessy was to use, how far it reached into the application, how much code it needed, and where it
failed us. The measurements are from the `main` branch on 2026-10-03. Each finding was checked against Nessy's
source, and two were corrected: a claimed gap that Nessy does not have was removed, and F3 was
narrowed.

## The verdict

Nessy fits case-based work, where one agent works one case for days and people decide. The
queued door, the transactional `tell` and the policy approver do real work that we did not have to
write. The gaps are in three areas:
- the approval life cycle after a person decides;
- reading an agent's history and usage from outside the engine;
- trust in the input.

Each gap cost us application code. The dispatcher stall (F8) was a release blocker. Its fix is on
Nessy `main` and must ship in the next release.

## How much code, and whose

| Module | Code lines | Nessy in it |
|---|---|---|
| `erp-sim` (the ERP simulator) | 2,383 | none |
| `ap-agent` (the desk) | 2,829 | 17 of 51 files |
| `ap-eval` (the evaluation) | 932 | none |
| `ap-contracts` (shared events) | 54 | none |

"Code lines" excludes blank lines, comments, imports and package lines.

Inside `ap-agent`, the code divides like this (estimates by file):

| Kind of code | About | Note |
|---|---|---|
| Glue to Nessy | 250 lines | The agent type, the approver desk, the facts for the policy, the input renderer. |
| Tools | 430 lines | Mostly application logic: ERP reads, mail rules, limits. About a fifth is the `Tool` interface around it. |
| Decisions and their execution | 460 lines | Most of it any framework needs: a person decides, and the decision runs as that person in the ERP. About 100 lines exist only because of Nessy gaps (F1, F4, F7). |
| Everything else | 1,690 lines | The workbench, the mail route, the ERP client, the event listener, the case index. None of it depends on the framework. |

The application uses 23 Nessy types. Most of them come from the `api` package: `QueuedHarness`,
`Tool`, `ToolCallRequest`, `ToolResult`, `Awaited`, `ApprovalRequest`, `Replies` and
`ReplyToken`. One type comes from an internal package: `engine.store.TurnHistories`. That is a
gap (F3), not a choice.

**About 90% of `ap-agent` would exist with any agent framework.** The part that is Nessy's is
small. The part that Nessy made us write is smaller, but it is in the places that matter most:
decisions and audit.

## How invasive it is

- **Nessy stays in one module.** The ERP, the evaluation, the Camel route, Keycloak and OPA know
  nothing about it. The domain has no Nessy types.
- **Nessy shares the application's database and its transactions.** This is a strength: a
  RabbitMQ message, the case index and the `tell` to the agent commit together. It is also a
  coupling: Nessy's tables live in the application's schema, and an engine upgrade is a schema
  change.
- **Nessy is a snapshot dependency today.** Nessy AP builds against `0.4.0-SNAPSHOT`. The fixes it
  needs are on Nessy `main`, but not in a release. That is the biggest practical barrier for anyone
  else who wants to run this.

## What was easy

- **One agent for each case.** The queued door with a name-based `AgentId` matches case
  management. An agent waits for days with an empty backlog and costs nothing.
- **Exactly-once intake.** `tell` joins the caller's transaction. The event listener records the
  event id and tells the agent in one transaction, with no outbox of our own.
- **People in the loop.** `PolicyApprover` with OPA's `delegate` routes each proposal to a role,
  and `Replies` with a `ReplyToken` answers it days later. We did not invent an approval engine.
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
  id it cites. The desk answers that from the agent's turn history: what each tool returned. A
  framework that kept only the final answer could not support this check.
- **Decisions are records, not callbacks.** Each proposal is an approval request with an action,
  a rationale, evidence and the role the policy chose. Scoring "correct", "routed" and "evidence"
  is reading those records.
- **Agents have stable identities.** A case's agent is named from the exception id, and a
  reader's from the reply's Message-ID. The evaluation and the desk find every agent of a case
  without a lookup table in memory.
- **Narration made the tests deterministic.** Integration tests wait for `TurnEnded` instead of
  sleeping. A scripted model drives the real engine, so the tests cover the real queued door,
  approvals and the backlog.
- **The queued door's guarantees are testable as scenarios.** Exactly-once intake made the
  `redelivered` scenario meaningful: a repeated event must not start the case over, and 20 of
  20 runs proved it.

Where Nessy made the evaluation harder:
- **The story has no public read API (F3).** The evidence check and the audit trail use an
  internal engine type.
- **A failed turn is quiet (F15).** A dropped connection to the model ended turns, and the cases
  looked like an agent that gave up. Separating the system's failures from the model's took
  digging through logs.
- **No published test kit (F5).** Every application writes its own scripted model.

## Where it fought us

| Finding | What it cost us |
|---|---|
| **F1.** A tool cannot see its own approval. | A table of decisions, keyed by turn and call id, so that `propose_resolution` can find the decision that let it run. |
| **F3.** No read API for an agent's story. | The audit trail and the evidence check use an internal engine type. Usage is now readable, by model, through `UsageReports` (added to Nessy during this work); the story itself is not. |
| **F4.** A late decision has no channel. | A separate path that tells the agent after its approval expired. |
| **F5.** No scripted model for tests. | About 90 lines of test support that every Nessy application will write again. |
| **F6.** Narration cannot be joined to a tool call. | The application writes its own timeline for people to read. Fixed on Nessy `main` since. |
| **F7.** A call key is unique only within one agent. | A composite key in our own table. A silent collision if we had not read the code. |
| **F8.** The queued dispatcher could stop for good. | Found under load in the first live run. Every later case was told but never ran. Fixed on Nessy `main`. |
| **F9.** A person cannot reach an agent while its proposal waits. | A note from the workbench waits until the decision is made, which is the wrong order for AP. |
| **F10.** `Turn.tokens` was always 0. | The evaluation reads the metric before and after each case, which works only while cases run one at a time. The field is removed on Nessy `main`. |
| **F11.** Inputs carry no provenance. | The application frames untrusted text itself. A prompt injection still persuaded the agent in 4 of 5 runs. |
| **F12.** Nothing checks that the policy knows a gated tool. | A new tool met an old policy, and a fraudulent vendor received mail. The policy now denies any tool it does not name. |
| **F13.** Stored history has no retention or cleanup. | The quarantined reader's history holds the text of every reply, outside Occlude. Nessy's storage codec encrypts it (the desk uses codec-crypto), but nothing expires it, and Occlude's erasure cannot reach it. |
| **F14.** The direct door fails inside a caller's transaction, and nothing anticipated it. | We called it inside the mail route's transaction (our mistake: a model call held a transaction open). Every live read failed with an internal error, and no test saw it. The reader now suspends the transaction. |
| **F15.** A dropped connection to the model ends the turn. | The OpenAI adapter reports a transport failure as "unknown", and the engine retries only "transient" failures. Under parallel load on LM Studio, each dropped request left a case with nobody acting on it. |

Two smaller points:
- **The `Tool` interface is verbose for simple tools.** Each tool implements four methods.
  `ErpTools` hides this behind a helper, but a small builder in Nessy would remove it.
- **Judgment is the model's part, and Nessy cannot fix it.** On the small local model, failures
  were judgment (a missing PO, a persuasive reply). On the larger hosted models, the failures were
  the desk's own (a playbook rule, the scorer) and copied ids. Nessy's job is to make the
  deterministic controls easy to add around the model, and the policy approver does that well:
  the gate on ungrounded citations was one enricher fact and one policy rule.

## What would make Nessy a better fit

In order of value to this application:
1. **Release the fixes on `main` (F6, F8, F10).** Nobody can build on the queued door without F8.
2. **Make approvals complete:** a tool sees its approval (F1), a late decision has a channel (F4),
   and call keys are unique (F7). Together these would remove about 100 lines and a class of bug.
3. **A read API for an agent's story (F3).** Usage is done (`UsageReports`); the story is not. Audit and cost are the
   first questions an enterprise asks.
4. **Provenance on input (F11).** Let an application say how far each input is trusted, so that
   the renderer, the policy and the trail can use it. The Occlude experiment in this repository
   is a candidate design.
5. **A published test kit (F5)** with a scripted model and a narration tap.
6. **Input while a proposal waits (F9).**
