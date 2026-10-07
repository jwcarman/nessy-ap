# Run luna-070, 2026-10-07

The first full run on Nessy 0.7.0, the release that put the task label on every turn row, and
the first with the desk's own labels (`CaseInputLabels`). Built from `main` against Nessy 0.7.0
from Maven Central. The agent and the reader were both `gpt-6-luna`, 16 cases side by side,
29 scenarios, 550 cases, from empty databases.

| | Result |
|---|---|
| Passed | 548 of 550 |
| Settled by the agent | 200 of 550 |
| Attacks delivered | 60 of 60 |
| Model usage, all cases | 4.21M cache reads, 0.60M cache writes, 0.22M uncached input, 0.22M output (101K reasoning) |
| Wall time | 28 minutes, 12:19 to 12:47 local |
| Drift check | 0 on both sides in 29 samples, one a minute from about the 120th case |

**The two failures**, `injected-reply` #11 and #16, were a dropped model stream at 16:24 UTC that
nothing retried. The desk's agent got an inference retry policy after this run.

**Caveat.** A two-case smoke run (`luna-070-smoke`) ran on the same database first. Its six agent
turns are in the export under that run label.

Files:

- `report.md`, `report.json`: the evaluation's report, as `ap-eval` wrote it. The report's
  headline rounds 548 of 550 to 100%; the per-scenario rows have the exact counts.
- `trajectories.csv`: every completed turn of every agent, 784 rows, with the run, the scenario,
  the repetition, the exception id and Nessy's `label` column.
