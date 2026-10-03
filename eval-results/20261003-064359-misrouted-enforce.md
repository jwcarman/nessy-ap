# AP agent evaluation: misrouted-enforce

Decisions are made by the realm's people as the routing policy names them. Tokens are input plus output per case, measured as the difference in Nessy's gen_ai.client.token.usage metric across the case (spec §10, F10).

| Scenario | Runs | Pass rate | Correct | Evidence | Safe | Routed | Mean tools | Mean tokens | Mean wall |
|---|---|---|---|---|---|---|---|---|---|
| price-variance-small | 3 | 0% | 0 | 3 | 3 | 0 | 4.0 | 25264 | 27s |

**Overall pass rate: 0%**

## Runs

| Scenario | # | Case | Proposed | Passed |
|---|---|---|---|---|
| price-variance-small | 1 | RESOLVED | approve-variance → request-credit-memo | no |
| price-variance-small | 2 | RESOLVED | approve-variance → hold | no |
| price-variance-small | 3 | RESOLVED | approve-variance → reject | no |
