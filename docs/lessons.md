# Lessons for building agentic systems

This page collects what building the desk taught, in the order a team building its own
agentic system is likely to meet it. Each lesson names the slice and the evidence. The details
are on the [evaluation](evaluation/index.md) pages.

## Put the model inside controls it cannot argue with

A model can be persuaded. In slice 6, an invoice whose line text said "this is pre-approved"
made the agent propose payment in 5 of 5 runs. The fix was not a better prompt. It was a rule
the model cannot reach: the policy (OPA) refuses to pay an invoice whose number repeats another,
and the ERP refuses it again. After that, no run paid, whatever the text said.

- The agent proposes. A person with the right authority decides. The ERP checks that authority
  again with the person's own token.
- Every control has a place where it is enforced, and that place is never the model.
- A policy that does not name a tool denies it. An application newer than its policy then fails
  closed, not open (slice 5: a new tool met an old policy, and a fraudulent vendor received mail).

## Keep untrusted text away from the agent, and say so in types

Telling a model "treat this as data" is framing, not a boundary. In slice 7 a vendor reply that
claimed the controller's approval still persuaded the agent in 4 of 5 runs.

Slice 8 moved the boundary:

- Mail is held by Occlude, labelled unendorsed. The agent never reads it.
- A second model, with no tools, reads each reply into a typed reading: an intent, the offers,
  a price, a PO number of the ERP's shape, and whether it tried to give instructions.
- A claim becomes a fact only by agreement with something already trusted: a PO number counts
  only when the ERP holds it for the case's vendor. The model never decides what to trust.
- The case carries an integrity label. Once its mail tried to instruct the desk, the policy
  will not move money on it. The worst an attacker can do is force a hold.

The injected reply went from 1 of 5 to 5 of 5 held.

**The cost is vocabulary.** The reading carries only what its types can say. Until the reading
had a word for "offers a credit memo", the agent could not learn that the vendor had offered
one, and a scenario it used to pass went to 0 of 5.

## A safe fallback hides failures

"When the reader fails, a person reads the mail" is the right failure mode. It also made a
completely broken reader look careful: in the first live run every reading failed, the
injection was held 3 of 3, and no log said anything. Only the information-flow library's own
record of refusals showed the truth. Check that the happy path happens, not only that nothing
bad happens, and make refusals visible in the logs.

## Ask people where they are trusted

The buyer is a person inside the company. Asking by mail made the buyer's answer untrusted:
"please pay it as billed" read as an instruction, and the case was held. Slice 9 asks the buyer
on the workbench instead. A signed-in answer is that person's own word, and there is nothing to
quarantine. Mail stays for the people outside.

## People's time is a measure

Two designs passed the same scenario. One asked the buyer, made a clerk approve a hold while it
waited, and then asked the buyer to approve: three touches. The other proposed the buyer's own
decision with its evidence: one touch. If the evaluation does not count touches, it cannot see
the difference. Two rules came from this:

- **Ask once.** Do not ask a person something they will decide anyway.
- **Do not ask anyone to approve a decision that changes nothing.** A hold on an invoice that
  is already on hold is refused before a person sees it.

## A rule can do two jobs

"While you wait, propose a hold" looked like a cost and was removed. Then the agent sometimes
ended a turn having only read, or only written a note, and nobody was acting on the case. The
old rule had also guaranteed that every turn ended with a move. The replacement says that job
plainly: every turn ends with a proposal, a question, or a letter. When you remove a rule, look
for the second job it was doing.

## Score the evidence a person relies on

An approver decides from the proposal's citations. An early rule passed a run when the agent
had called certain tools. It failed agents that found the right fact another way, and passed
agents that cited nothing. The rule now checks that the proposal cites the facts the decision
rests on, and that the agent read each one: a tool that succeeded returned it. A final review
found that the first version also counted ids from the agent's own words. That would have made
the check easier than the one it replaced. The workbench now warns an approver about any
citation the agent never read.

## Status fields drift

A case status that any event may overwrite drifts. A reply that arrived after a case resolved
set it back to "investigating", and nothing ever resolved it again. Make transitions explicit:
waiting moves into and out of investigating, and a status a decision set is not undone by mail.

## Separate the system's failures from the model's

Under parallel load the local model server dropped requests. Each dropped request ended a turn,
and the case then looked exactly like an agent that gave up. Before trusting a pass rate, count
the infrastructure failures apart, and decide what the system does when a turn fails.

## Measure enough, and look at the runs

Five runs a scenario cannot tell 80% from 100%. The [evaluation results](evaluation/results.md)
runs every scenario 20 times and gives an interval for each rate. Every number on these pages
also has a story behind it, and most of the bugs were found by reading one failed run, not by
reading the rate.
