# Stay deterministic as long as you can: the resolver

Status: draft for James's review. Date: 2026-10-04.

This spec adds a deterministic layer in front of the agent. The layer settles every case that
rules can settle, gathers the facts that rules need, and gives the agent only the cases that
rules cannot frame. It amends the design of record
([2026-10-02-ap-exception-desk-design.md](2026-10-02-ap-exception-desk-design.md)).

## 1. Why

The evaluation results show that most of the agent's work is a decision table:

- In the last full run on gpt-6-luna, 16 of 21 scenarios never wrote to anyone. The agent read
  the ERP and proposed.
- In 13 of those 16, the agent proposed the same action in all 20 runs.
- In the other 3, it divided its runs between two acceptable actions (for example, a credit memo
  17 times and a short-pay 3 times). That is a policy decision that nobody made, not judgment.
  The same vendor gets different treatment on different days.
- The playbook's "By reason code" section is a decision table written as a prompt. Several of its
  rows are exact rules ("NO_RECEIPT: hold"), and the policy enforces some of them a second time.
- The cheapest model matched the largest models on every judgment scenario. Easy decisions in a
  structured domain are rule-shaped.

James's rulings in the design discussion (2026-10-04):

- Once inference has classified an input and filled typed slots, processing must be deterministic.
- If the expected output of an evaluation is a function of structured input, that function is
  code.
- A rule that can be decided from the ERP's own data belongs in the ERP. The desk's rules cover
  only facts the ERP does not hold: what people say, what counterparties write, and other
  systems. The simulator does not move the ERP-decidable rules into `erp-sim`; this spec states
  the assumption and keeps them in the desk's resolver (option a).
- The agent's first job, when rules stop, is to decide which facts to find.
- The resolver uses DMN decision tables, run by Apache KIE DMN as a library.

## 2. The layers

```
capture (inference: a document becomes fields)            outside this simulator
  -> ERP three-way match (rules)          match, or raise an exception with a reason code
    -> desk resolver (DMN rules over slots)   Resolved | NeedsFact | Escalate
      -> gathering loop (deterministic)       fill one slot, then ask the resolver again
      -> agent (judgment, inside checks)      only on Escalate
OPA policy, unchanged: who decides, and what is never allowed, for every proposal from any layer
```

Each layer handles what it can deterministically and passes on only what it cannot. The ERP's
exception is itself a rules engine's "escalate".

## 3. The resolver

**Engine.** Apache KIE DMN (`org.kie:kie-dmn-core`, latest GA at build time), in process in
`ap-agent`. Decision models are `.dmn` files under `ap-agent/src/main/resources/decisions/`.
A business analyst can edit them in a DMN modeler; the desk runs the same file.

**Input: slots.** A slot is a named fact with a value and a source, or unknown. Sources:

| Source | Example | Trusted when |
|---|---|---|
| ERP | `reasonCode`, `variancePercent`, `billedItem` | read |
| Reply reading | `substitutionReason` | the reader gave it and the ERP cross-checks pass (§5) |
| Person | `buyerDecision` | the person chose it on the workbench |

A rule fires only on known values. Unknown is not false.

**Output: exactly one of three outcomes.**

- `Resolved(action, amount, slotsUsed)`: the desk proposes the action. The evidence is the set of
  records behind the slots the rule used, filled in by the desk. Nothing is copied by a model.
- `NeedsFact(slot, from)`: the cheapest unknown slot that decides the case, and where to get it
  (`vendor` or `buyer`).
- `Escalate(why, known)`: `why` is one of `unhandled` (no row matched), `conflict` (rows with
  different outcomes matched), `exhausted` (a slot stayed unknown after it was asked for), or
  `invariant` (a fact broke an assumption of the table, such as two exceptions on one invoice).

**Convergence.** The resolution table uses the `UNIQUE` hit policy. No match is `unhandled`, an
overlap is `conflict`. A second table, with the `COLLECT` hit policy, lists the slots that each
reason code needs, so the desk can return `NeedsFact` before any resolution row can match.

## 4. Phase 1 scope

**4.1 The reason codes that need no correspondence.** These move from the playbook into the
resolution table:

| Reason code | Rule (from the measured runs and the playbook) |
|---|---|
| `PRICE_VARIANCE` | Within the buyer's limit: approve-variance. Above it: the policy choice in §9.1. |
| `QTY_OVER_RECEIPT` | The policy choice in §9.1 (hold, or short-pay for what was received). |
| `NO_RECEIPT` | Hold. |
| `DUPLICATE` | Reject, citing the original from `find_similar_invoices`. |
| `POSSIBLE_DUPLICATE` | One receipt for each invoice: approve-variance (the controller decides). Otherwise reject. |
| `UNPLANNED_CHARGE` | The policy choice in §9.1. |
| `VENDOR_BANK_CHANGED` | Hold, citing the vendor and the pending change. |
| A declined resolution | The declined action's alternative (§9.1), with the decision as a slot. |

The desk reads the ERP facts these rules need (invoice, PO, receipts, vendor, similar invoices)
directly, without a model.

**4.2 Case A: a substituted item.** The vendor shipped a different item because the ordered one
was out of stock, and billed it at a higher price.

- **ERP.** Invoice lines get an item code (vendor-written, so the desk shapes it like any other
  vendor reference). The three-way match compares it with the PO line's item and raises a new
  finding, `ITEM_SUBSTITUTED`.
- **Slots.** `orderedItem`, `shippedItem`, `poPrice`, `billedPrice` (ERP);
  `substitutionReason`, `vendorStatedPrice` (reply reading); `buyerDecision` (person:
  `ACCEPT_AT_BILLED`, `ACCEPT_AT_PO_PRICE` or `REJECT`).
- **Rules.** Accept at the billed price, within the buyer's limit: approve-variance. Accept at
  the PO price: short-pay to the PO price. Reject: request-credit-memo (the goods go back).
- **Needs.** No reason yet: write to the vendor. A reason but no decision: ask the buyer.
- **Escalate.** The reader is `UNCLEAR`, a cross-check fails, the buyer answers in free text, or
  lines are substituted with different answers.

**4.3 Not in phase 1.** The `NO_PO` family stays with the agent. So do every scenario that
escalates, and the agent's "fact report" mode (the agent returns slots and the rules decide).
Phase 2 measures whether that mode beats the agent proposing.

## 5. The reader

The reader's typed answer gains `SUBSTITUTED_ITEM` and `UNCLEAR` intents and a `substitution`
slot (`orderedItem`, `shippedItem`, `reason`). Rules:

- `UNCLEAR` is a first-class answer. The reader's instructions prefer it to a guess.
- Every slot may be null, which means unknown.
- No confidence numbers: a model's own confidence is not calibrated.
- **Cross-check.** A claimed item, price or PO becomes a slot only when the ERP agrees: the
  ordered item is the PO line's, the shipped item is the invoice line's, the stated price is the
  billed price. A mismatch leaves the slot unknown.
- **Agreement.** For a slot that moves money, the reader reads the reply twice. A disagreement
  leaves the slot unknown.

## 6. The gathering loop

When the resolver returns `NeedsFact`, the desk fills the slot without a model:

- `from: vendor`: the desk writes to the vendor's contact of record from a template, through the
  existing mail path, and waits. The reply goes through the quarantine and the reader as now.
- `from: buyer`: the desk puts a multiple-choice question on the buyer's worklist through the
  existing questions path.

The desk asks for each slot once. A slot that stays unknown after its answer becomes `Escalate:
exhausted`. A counterparty that does not answer leaves the case waiting, as now
(`AWAITING_ANSWER`).

## 7. Proposals without an agent

A `Resolved` outcome becomes a proposal through the same path as the agent's: the same facts
enricher, the same OPA decision, the same workbench decision, the same ERP command in the
decider's name. The difference is that no agent waits for the answer. When the decision is
applied, the case resolves. When it is declined, the decline becomes a slot and the resolver runs
again. The rationale is a template per rule, filled from the slots.

## 8. The handoff to the agent

`Escalate` tells the case's existing agent, unchanged except for its first input: a new case
input that says what the rules established, which slots are unknown, and why the rules stopped.
The agent then works as today, under every existing control.

## 9. Open questions for James

1. **The unmade policy decisions.** Pick one action for each:
   - A price variance above the buyer's limit, unexplained: credit memo or short-pay?
   - Billed more than received: hold for the rest, or short-pay for what arrived?
   - An unplanned charge (freight not on the PO): approve, or short-pay without it?
   - A declined approval of a variance: credit memo or short-pay?
2. **One touch or two.** When the only missing slot is the decider's own choice
   (`buyerDecision`), should the question be the decision, with the buyer's pick as the approval
   (one touch), or does the buyer answer and then approve the proposal (two touches)?
3. **The SonarCloud CSRF findings.** Mark the two S4502 findings as reviewed and safe in
   SonarCloud, with the justification, or another way?

## 10. New concepts for sign-off

Each of these is new vocabulary or a new public type:

- The **resolver**, and its three **outcomes**: `Resolved`, `NeedsFact`, `Escalate`.
- The **slot**: a fact with a value and a source, or unknown.
- The reason code **`ITEM_SUBSTITUTED`**, and an **item code** on invoice lines.
- The reader's **`SUBSTITUTED_ITEM`** and **`UNCLEAR`** intents.
- A **case input** for the agent: "the rules stopped here".
- A proposal **with no agent behind it**.
- In the evaluation, **`settledBy`**: which layer settled a run.

## 11. The evaluation

- **Per-layer attribution.** Each run records which layer settled it: `rules`, `rules with
  gathered facts`, `agent`, or `person` (`NEEDS_PERSON`).
- **Determinism.** A run settled by rules must be the same in every repetition. Any difference is
  a bug, not a rate.
- **New scenarios.** A1: everything fits, settled by rules and slots with no agent. A2: the
  vendor's reply is unclear, escalated to the agent. A3: the buyer's answer does not fit the
  choices, escalated.
- **The existing 21.** Same expected outcomes. The report shows the agent's share falling from 21
  scenarios to the ones that escalate.
- **The resolver's own tests.** Each DMN table has unit tests that need no model and no
  container: every row, every `NeedsFact`, and the `unhandled` and `conflict` outcomes.

## 12. The case-study chapter

A new page, "Stay deterministic as long as you can", tells the story with the numbers: the
finding (16 of 21 scenarios needed no correspondence), the principles above, the layers, how the
resolver knows to stop, and the per-layer chart from the first full run.
