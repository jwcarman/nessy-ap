# The run ledger

Every full run of the evaluation, whether or not the other pages discuss it. A row is the record;
the narrative pages choose what to tell. Smoke runs are not here.

Each run's folder holds the evaluation's report, its JSON, and `trajectories.csv`: every
completed turn of every agent on the run, from Nessy's `nessy_agent_turn`, with the scenario, the
repetition and the exception id joined in. Reader turns carry no case, so their scenario is empty.
The queries that read the export are in `trajectory-queries.sql` beside this page's folders.

| Run | Date | Nessy | Models | Passed | Agent settled | Folder |
|---|---|---|---|---|---|---|
| `luna-070-retry` | 2026-10-08 | 0.7.0 | gpt-6-luna, agent and reader | 550 of 550 | 201 | [20261008-luna-070-retry](runs/20261008-luna-070-retry/README.md) |
| `luna-070` | 2026-10-07 | 0.7.0 | gpt-6-luna, agent and reader | 548 of 550 | 200 | [20261007-luna-070](runs/20261007-luna-070/README.md) |
| `luna-060` | 2026-10-07 | 0.6.0 | gpt-6-luna, agent and reader | 550 of 550 | 200 | [20261007-luna-060](runs/20261007-luna-060/README.md) |

Runs before 2026-10-07 have no folder: their reports were written to `target/eval-results`,
which a `clean` deletes, and only what [the results](results.md) and
[how the desk evolved](how-the-desk-evolved.md) copied out survives. The `luna-060` report was
lost the same way, hours after the run; its trajectory export survived. That loss is why this
ledger exists.

The reports are written by `ap-eval`; copying them here is still a manual step after each run.
