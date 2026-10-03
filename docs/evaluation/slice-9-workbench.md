# Slice 9: people answer on the workbench

**The change.** People inside the company are asked on the workbench, not by mail. The agent's
`ask_buyer` tool puts a question on the worklist of the buyer the ERP names on a PO that belongs
to the case's vendor. The buyer answers signed in, with a click or a short note, and the answer
reaches the agent as that person's own word. A short notice mail says that a question waits; it
carries no question and no case token. Mail stays for vendors only.

Three rulings came with it:

- **Ask once.** When the decision would be the buyer's own anyway, the agent proposes it with its
  evidence and does not ask first.
- **No interim hold.** Asking needs no hold, because the invoice is already stopped by its
  exception. A case that waits on someone says so (`AWAITING_ANSWER`).
- **Nobody approves a decision that changes nothing.** The policy refuses a hold on an invoice
  that is already on hold, before anyone is asked.

## Results

60 of 70 (86%) against 53 of 70 on slice 8, run `20261003-134231-slice9-full`.

| Measure | Slice 8 | Slice 9 |
|---|---|---|
| Correct outcome | 61/70 | 67/70 |
| Safe | 68/70 | 70/70 |
| Never resolved | 3 | 0 |
| Wall time, whole run | about 65 minutes | about 31 minutes |

Everything the buyer touches went to 5 of 5. The run time halved because cases settled instead
of timing out.

## What the runs taught

**A smoke test is a test.** The first smoke run on slice 9 had a case stuck for five minutes. The
agent had asked the buyer, proposed a hold while it waited, and after the answer proposed a
second hold on the same invoice. A clerk approved it, the ERP refused it, and the case stayed
"investigating". Two lessons: never ask a person to approve a decision that changes nothing, and
never let a status set by a decision be undone by mail or an answer.

**People's time is a measure.** Before "ask once", a small price variance cost three touches: the
buyer answered, a clerk approved a hold, and the buyer approved the variance. With "ask once" it
costs one. Both passed. The evaluation now reports human touches per case.

**A rule that was holding the system up.** Without the interim hold, the agent sometimes ended a
turn having only read, or only written a note. Nobody was then acting on the case. The old rule
("propose while you wait, usually hold") had also been doing a second job: every turn ended with
a move. The case input now says so in its own words: every turn ends with a proposal, a
question, or a letter.

**Evidence by facts.** Seven of slice 9's ten failures were correct rejections of a duplicate.
They failed because the agent found the original invoice with a second read, not with the
"required" search tool. The evidence rule now checks what the proposal cites against what the
agent read. A final review then found that the first version of that check counted ids from
the agent's opening message and from its own words. Now only what a tool returned counts, as a
whole token.

**Parallel runs.** Usage per case now comes from Nessy's stored history (`UsageReports`, a
projection over each agent's events) for every agent on the case. Cases no longer need to run one
at a time. On LM Studio the speed-up is limited by the model server, and at four cases at a time
the server dropped requests. Each dropped request ended a turn, because Nessy does not retry an
inference whose outcome is unknown (finding F15).
