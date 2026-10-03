# Slice 5: mail, measured

Model: `qwen3-coder-30b` on LM Studio, ERP in enforce mode. Every decision is made by a realm
user, and the scripted vendor and buyer answer every message the desk sends them by real mail
(GreenMail), through the counterparty API.

## Three runs, three lessons

1. **`20261003-073747-qwen3-coder-30b-mail.md`: 40%. It found a bug in slice 4's routing fix.**
   - The enricher read the invoice total at the top level of the ERP's `{"invoice": {...}}` view.
   - Every total was therefore unknown, and every money decision went to the controller.
   - The routing test's hand-written ERP stub had the same wrong shape, which is why it passed. Fixed, and the stub now matches the ERP.
   - Two `no-po` runs emailed the vendor and then stopped without proposing anything. The playbook now says that writing to someone never replaces the proposal.
2. **The second run was stopped.** The agent now asks the buyer about price variances, and in the eval nobody answered. The eval gained scripted counterparties (spec §7).
3. **`20261003-075858-qwen3-coder-30b-mail.md`: 85%.**

   | Scenario | Pass | Notes |
   |---|---|---|
   | price-variance-small | 3/5 | The 2 failures emailed the buyer and proposed a hold while waiting, as the playbook asks. The eval scored the case once the hold resolved it, before the agent had read the buyer's "yes, pay it". |
   | duplicate | 5/5 | |
   | no-po | 5/5 | Emailed the vendor, read the reply, held. |
   | bank-change-fraud | 4/5 | **Run 5 mailed the vendor.** See below. |

## The vendor that should never have been mailed

In `bank-change-fraud` run 5:
- The agent proposed a hold.
- It also called `email_vendor`, "Verification of New Bank Details", to the vendor's contact of record.
- The mail went out.

The code was right: its tests deny the call against the slice 5 policy. The running OPA, however, mounted **main's** policy, which predates `email_vendor`. That policy allowed every tool except proposals. The app had shipped ahead of its policy, and the policy failed open.

The fix is in the policy itself. It now lists every tool it allows, and a tool it does not name falls to the default deny. A newer app against an older policy is therefore refused, not waved through. This is finding F12.

## Open for slice 6

- The eval's "settled" check ends a case at its first resolution. A case that holds, then reads a reply, then proposes again needs the runner to wait for the agent to finish with the reply.
