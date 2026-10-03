# Slice 6: Full Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ap-eval` scores the agent across the whole exception catalogue and under the failures a real AP desk meets, and reports a comparison across local models.

**Architecture:** The work is mostly in `ap-eval`:
- A scenario accepts any of several correct resolutions, each with the role it must be routed to.
- The runner decides a case is finished only once the agent has gone quiet after the last thing anyone told it.
- Failure variants reuse the base scenarios with a twist: a silent counterparty, flaky ERP reads, or a redelivered event.

`erp-sim` gains two things: a 429 fault status, and an admin endpoint that re-publishes an exception's event. `ap-agent` learns to read a 429.

**Tech Stack:** as before. LM Studio only: the model matrix compares local models. Frontier models (Haiku, Sonnet) need James's go-ahead to spend.

**Spec:** §7 (evaluation), §2.7 (faults), §8 (failure handling), §11 item 6.

## Global Constraints

- Everything in slices 1 to 5 holds.
- **No paid inference.** Only LM Studio models run.
- **Out of scope tonight:**
  - Approval expiring mid-decision and ap-agent restarting between proposal and decision. Both need the eval to restart or reconfigure the apps.
  - Stale-read windows.

  Each is listed as open in the report.
- A failure variant is a scenario. It has its own name in the report, and its twist is applied before seeding and cleared after the run, even when the run fails.

## Review Focus

1. **A run whose fault rule is left in place poisons every later run.** Faults are cleared in a `finally`.
2. **A case that holds, then reads a reply, then proposes again** is scored on its final proposal, not on the hold.
3. **A redelivered event opens one case and one proposal.** It is scored as "exactly one proposal".
4. **A 429 from the ERP** is shown to the model with the wait the ERP asked for, never as a crash.
5. **The quiet-period rule does not wait the full timeout on every run.** A run ends a few seconds after the last activity.

---

### Task 1: Acceptable resolutions and the settle rule

- `Scenario`: replace `expectedAction` and `expectedRole` with `Map<String, String> acceptable` (action → role). Add `boolean singleProposal`.
- `Scoring`:
  - correct = the last action is in `acceptable`;
  - routed = the last route equals `acceptable.get(last action)`;
  - `singleProposal` makes more than one proposal fail safety.
- `Runner`: a case is finished when all of the following hold:
  - it is RESOLVED;
  - every decision is ANSWERED;
  - every scripted reply has been sent;
  - no timeline event has arrived for `--quiet` (default PT8S).
- `Report`: show the acceptable set, not one action.

**Tests:**
- `ScoringTest`: an alternative acceptable action passes; an acceptable action routed to the other action's role fails; a second proposal fails a single-proposal scenario.
- The settle rule is pure; extract `Settled.of(view, now, quiet, repliesPending)` and test it.

### Task 2: The whole catalogue

New scenarios, with acceptable resolution → role. Routing follows the ERP's amount, which is the invoice total:

| Scenario | Acceptable resolutions | Required tools | Replies |
|---|---|---|---|
| price-variance-large (PO 40 × 250, billed 290) | request-credit-memo → ap-clerk; short-pay → ap-manager | get_invoice, get_purchase_order | vendor: "The price rose with our costs; we can issue a credit memo if you insist." |
| qty-over-receipt (60 of 100 received) | hold → ap-clerk; short-pay → ap-manager | get_receipts | buyer: "The rest ships next week." |
| no-receipt | hold → ap-clerk | get_receipts | buyer: "Nothing has arrived yet." |
| unplanned-freight ($85 freight) | approve-variance → ap-manager; short-pay → ap-manager | get_invoice, get_purchase_order | buyer: "Freight was agreed by phone." |

The playbook is unchanged unless a scenario shows it is silent on something.

### Task 3: Failure variants

- **erp-sim:** `POST /admin/exceptions/{exceptionId}/redeliver` clears `published_at` on that exception's `match-exception.raised` outbox row, so the same event (same event id) is published again.
  - Test: the row is published twice and carries the same id.
- **erp-sim:** `FaultRule` gains `status` (default 503; 429 allowed). A 429 carries `Retry-After: 2`.
  - Test: a 429 rule answers 429 with the header.
- **ap-agent:** `ErpClient` maps 429 to `Unavailable("rate limited by the ERP; retry after Ns")`.
  - Test against `ErpStub`.
- **ap-eval** variants (`Scenario.twist`, applied by `Runner`):
  - `silent-buyer`: price-variance-small with no replies. Acceptable: approve-variance → buyer, hold → ap-clerk.
  - `flaky-erp`: duplicate with 30% 503 on `/api/vendors/**` and `/api/purchase-orders/**` and 429 on `/api/invoices/similar` at 50%. Same acceptable set as duplicate. Faults are cleared in a finally.
  - `redelivered`: duplicate, with the event redelivered 3 s after seeding, single proposal.

### Task 4: Live runs and the local model matrix

- Full suite × 5 on `qwen/qwen3-coder-30b`.
- The same on each other loaded local model that answers a smoke test (one case). A model that cannot use tools is reported as such, with no run.
- Report: `eval-results/20261003-slice-6-matrix.md`.
