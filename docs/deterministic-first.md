# Stay deterministic as long as you can

The desk started as an agent with a playbook. Every exception went to a model, and the model read
the ERP, asked people, and proposed. The evaluation then showed that most of that work was a
decision table. This page tells what the desk does now: rules settle what rules can settle, and
the agent gets only the cases that rules cannot frame.

The design is in `docs/superpowers/specs/2026-10-04-deterministic-resolver-design.md`.

## What the evaluation showed

The last full run before this change was on `gpt-6-luna`, with 21 scenarios and 20 runs of each:

- In 16 of the 21 scenarios, the agent wrote to nobody. It read the ERP and proposed.
- In 13 of those 16, it proposed the same action in all 20 runs.
- In the other 3, it divided its runs between two acceptable actions, for example a credit memo
  in 17 runs and a short-pay in 3. Nobody had decided which one the company wants, so the same
  vendor got different treatment on different days. That is a policy decision that nobody made,
  not judgment.
- The playbook's "By reason code" section was a decision table, written as a prompt.
- The cheapest model matched the largest models on every one of these scenarios.

If the expected output of an evaluation is a function of structured input, that function is
code. The evaluation had written the function down already: it was the list of acceptable
outcomes for each scenario.

## The principles

- **Once inference has classified an input and filled typed slots, process it with rules.** A
  model reads the vendor's mail into a typed reading. What the desk does with that reading is a
  rule.
- **A rule that the ERP's own data decides belongs in the ERP.** In a real company, the AP team
  would ask for these rules in the ERP. The simulator keeps them in the desk, and this page says
  so: the desk's rules stand in for rules that the ERP does not have.
- **The rules must know when to stop.** A rules engine that cannot decide must say so, and must
  never guess.
- **When the rules stop, the agent's first job is to decide which facts to find.**
- **Two engines, two jobs.** The decision tables decide what to propose. The policy (OPA) decides
  who may approve it and what is never allowed. A wrong row in a decision table cannot authorize
  what the policy forbids, because every proposal goes through the same policy.

## The layers

```mermaid
flowchart TB
  CAP[Capture: a document becomes fields<br/>outside the simulator]
  ERP[ERP three-way match: rules<br/>match, or raise an exception with a reason code]
  RES[Desk rules: DMN decision tables<br/>Resolved, NeedsFact or Escalate]
  ASK[Ask for one fact<br/>a letter from a template]
  READ[Reader: a model with no tools<br/>the reply becomes a typed reading]
  AGENT[Agent: judgment inside the controls]
  OPA{{OPA policy<br/>who decides, and what is never allowed}}
  PERSON([A person decides])

  CAP --> ERP --> RES
  RES -- NeedsFact --> ASK --> READ -- a checked slot --> RES
  RES -- Escalate --> AGENT
  RES -- Resolved --> OPA
  AGENT -- proposal --> OPA
  OPA --> PERSON
```

Each layer does what it can deterministically, and passes on only what it cannot. The ERP's
exception is itself a rules engine that says "I cannot decide this".

## How the rules know to stop

The rules are two DMN decision tables in `ap-agent/src/main/resources/decisions/resolution.dmn`.
Apache KIE DMN runs them in the desk's process, as a library. A business analyst can open the same
file in a DMN modeler.

- **`needs`** (hit policy COLLECT) lists the facts that a case needs before any resolution can be
  chosen. The first one becomes `NeedsFact`. Today it has one row: a substituted item with no
  reason yet needs the reason, from the vendor.
- **`resolution`** (hit policy UNIQUE) must match exactly one row. One match is `Resolved`. No
  match is `Escalate: unhandled`. Two matches are `Escalate: conflict`: the rules disagree, so
  they do not choose.

A fact that the desk could not read is unknown, and unknown is not false. A row fires only on
known values. When an ERP read fails three times, the fact stays unknown, no row matches, and the
case goes to the agent.

The rules stop for these reasons. The agent's first input names the reason:

| Reason | What happened |
|---|---|
| `unhandled` | No row covers what the rules know. |
| `conflict` | Two rows match and disagree. |
| `exhausted` | A fact the rules asked for did not come back in a form they can check. |
| `refused` | The policy refused what the rules proposed. |
| `invariant` | A rule needs an amount that the desk could not compute. |
| `person` | A person wrote a note to the agent on the workbench. |

## What the rules settle

| Reason code | Rule | Who decides |
|---|---|---|
| `PRICE_VARIANCE`, up to 10% over | approve-variance | the PO's buyer |
| `PRICE_VARIANCE`, more than 10% over | request-credit-memo | a clerk |
| `PRICE_VARIANCE`, declined by the buyer | request-credit-memo | a clerk |
| `QTY_OVER_RECEIPT` | hold | a clerk |
| `NO_RECEIPT` | hold | a clerk |
| `DUPLICATE`, the original found | reject, citing the original | the AP manager |
| `POSSIBLE_DUPLICATE`, a receipt for each invoice | approve-variance | the controller |
| `POSSIBLE_DUPLICATE`, one receipt | reject | the AP manager |
| `UNPLANNED_CHARGE` | short-pay without the charge | the AP manager |
| `VENDOR_BANK_CHANGED` | hold, citing the vendor and the pending change | a clerk |
| `ITEM_SUBSTITUTED`, the vendor's reason known | approve-variance | the PO's buyer |
| `ITEM_SUBSTITUTED`, declined: keep the goods | short-pay to the PO price | the AP manager |
| `ITEM_SUBSTITUTED`, declined: return the goods | request-credit-memo | a clerk |

The business rules follow common AP practice. People can argue with them, and the table is the
place to change them. A large variance that nothing explains gets a credit memo, because a
short-pay leaves a disputed open balance. Freight that the PO does not have is not paid.

`NO_PO` stays with the agent. Finding the right order for an invoice that cites none is the kind
of work that needs judgment.

## A case from the long tail: a substituted item

The vendor was out of stock of the zinc M8 bolts that the PO ordered at 10.00. It shipped
stainless bolts and billed them at 11.20. The ERP compares each invoice line's item code with its
PO line's item and raises `ITEM_SUBSTITUTED`.

1. The rules read the invoice, the PO, the receipts and the vendor from the ERP. They know the
   reason code and the billed item. They do not know why the vendor substituted it.
2. `needs` returns `NeedsFact: substitutionReason, from the vendor`. The desk writes one letter
   from a template to the vendor's contact of record, and the case waits for the answer.
3. The vendor answers. The reply goes through the quarantine, as all mail does. The reader, a
   model with no tools, reads it into a typed reading: the intent `SUBSTITUTED_ITEM`, the reason
   `OUT_OF_STOCK`, and the item shipped.
4. The desk checks the reading against the ERP. The item that the vendor says it shipped must be
   the item on the invoice line. Only then does the reason become a fact.
5. `resolution` returns approve-variance. The desk proposes it with a rationale from a template and
   the ids it read as evidence. The policy routes it to the PO's buyer.
6. The buyer approves on the workbench, and the ERP applies it in the buyer's name. That is one
   touch for a person.

If the buyer declines, the workbench asks what to do instead: keep the goods at the PO price, or
return them. That answer is a fact too. The rules run again and propose the short-pay or the
credit memo, which goes to its own decider.

If the vendor's answer cannot be checked ("Please see the attached"), the reader says `UNCLEAR`.
The reader is told to prefer `UNCLEAR` to a guess, and the desk does not ask twice. The rules stop
with `exhausted`, and the agent gets the case with what the rules established.

## Proposals with no agent behind them

A proposal from the rules goes through the same path as the agent's:

- the same facts for the policy, from the same enricher;
- the same OPA decision: who decides, or a refusal;
- the same workbench decision, and the same ERP command in the decider's name.

The differences:

- No agent waits for the answer. When the decision is applied, the case resolves. When it is
  declined, the decline becomes a fact and the rules run again.
- The rationale is a template for each rule, filled from the facts.
- The evidence is the ids the desk read from the ERP itself. A model copies nothing, so the
  grounding check has nothing to catch: the rules' proposals are grounded by construction.
- The workbench says "The desk rules propose" instead of "The agent proposes".

## The handoff to the agent

When the rules stop, the desk gives the case to its agent for good. The agent's first input is
the exception, the reason the rules stopped, and every fact they established. From then on, the
agent works as before, under every control it had before. The rules do not take the case back.

A receipt that arrives reaches only the cases that an agent works. A person's note on a case
that the rules work gives the case to its agent first, because a note is for the agent.

## How the evaluation measures it

- **Who settled each run.** The case view says who handles the case. The evaluation records
  `settledBy` for each run: `rules`, `rules+facts` when the rules asked someone first, or
  `agent`. The report shows the agent's share of all runs.
- **Determinism.** Every run that the rules settle of one scenario must end in the same action.
  The report names any scenario where they do not. A difference is a bug, not a rate.
- **The rules' own tests.** Each table row, the `NeedsFact` row, and the `unhandled` and
  `conflict` outcomes have unit tests that need no model and no container (`ResolverTest`).

## The first run

One run of each of 13 scenarios, on 2026-10-04, with local models in LM Studio:

| Who settled it | Scenarios | Passed | Model use per case |
|---|---|---|---|
| The rules | 9: both price variances, quantity over receipt, no receipt, duplicate, possible duplicate, unplanned freight, bank-change fraud, buyer denies | 9 of 9 | none |
| The rules, after one fact | 2: item substituted, substitute at the PO price | 2 of 2 | the reader only (below) |
| The agent | 2: no PO, substitution unclear | 0 of 2 | see below |

Usage, for each case that used a model, as LM Studio reported it (it reports no cache counts):

| Scenario | Model | Input | Output | Cache read | Cache write | Reasoning |
|---|---|---|---|---|---|---|
| item-substituted | `google/gemma-4-e4b` | 633 | 68 | — | — | 0 |
| substitute-at-po-price | `google/gemma-4-e4b` | 631 | 69 | — | — | 0 |
| no-po | `qwen/qwen3-coder-30b` | 45,302 | 853 | — | — | 0 |

The `no-po` case also used the reader for the vendor's reply.

Each case that the rules settled alone was resolved in about 10 seconds, wall time, including the
evaluation's own 2-second polling. Each run made one proposal for one person to decide, except
`buyer-denies`: there the buyer declined the first proposal, and the rules proposed a credit memo.

The two agent runs do not measure the agent. The agent's model, `qwen/qwen3-coder-30b`, was loaded
with LM Studio's default context of 8,192 tokens. The input above is the total over many requests,
so it does not show whether one prompt was cut short; that is not verified. On `no-po` the agent rejected
the invoice where the scenario expects a hold. On `substitution-unclear` it asked
the buyer, read the answer, and then stopped with no move. A full run on a frontier model is
still to do. One run of each scenario shows that a path works; it does not measure how often.

## What is not done

- **A second reading for facts that move money.** The design reads a reply twice for such a fact
  and leaves it unknown when the two readings disagree. Today the reader reads once. The item
  check against the ERP is the only cross-check.
- **Asking the buyer for a fact.** No rule needs one yet, so the rules ask only the vendor.
- **The agent as a fact finder.** In phase 2, an agent that the rules call returns facts, and the
  rules decide. The question is whether that beats the agent proposing.
- **A receipt for a case the rules hold.** A receipt that arrives does not run the rules again.
  The hold that the rules proposed stays in front of its decider.
