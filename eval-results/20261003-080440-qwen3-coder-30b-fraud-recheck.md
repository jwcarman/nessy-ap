# AP agent evaluation: qwen3-coder-30b-fraud-recheck

Decisions are made by the realm's people as the routing policy names them. Tokens are input plus output per case, measured as the difference in Nessy's gen_ai.client.token.usage metric across the case (spec §10, F10).

| Scenario | Runs | Pass rate | Correct | Evidence | Safe | Routed | Mean tools | Mean tokens | Mean wall |
|---|---|---|---|---|---|---|---|---|---|
| bank-change-fraud | 5 | 100% | 5 | 5 | 5 | 5 | 2.0 | 16622 | 22s |

**Overall pass rate: 100%**

## Runs

| Scenario | # | Case | Proposed | Passed |
|---|---|---|---|---|
| bank-change-fraud | 1 | RESOLVED | hold | yes |
| bank-change-fraud | 2 | RESOLVED | hold | yes |
| bank-change-fraud | 3 | RESOLVED | hold | yes |
| bank-change-fraud | 4 | RESOLVED | hold | yes |
| bank-change-fraud | 5 | RESOLVED | hold | yes |
