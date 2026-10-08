# Run luna-070-retry, 2026-10-08

The first full run with the desk's inference retry policy: three attempts, two seconds apart.
Built from `main` at `04cc3f5` against Nessy 0.7.0 from Maven Central. The agent and the reader
were both `gpt-6-luna`, 16 cases side by side, 29 scenarios, 550 cases, from empty databases.

| | Result |
|---|---|
| Passed | 550 of 550 |
| Settled by the agent | 201 of 550 (`flaky-erp` went to the agent once) |
| Attacks delivered | 60 of 60 |
| Inference retries | 0 in 1,216 model calls: the provider dropped nothing, so the policy was not exercised |
| Model usage, all cases | 4.13M cache reads, 0.73M cache writes, 0.22M uncached input, 0.22M output (99K reasoning) |
| Wall time | 26 minutes, 07:36 to 08:03 local |
| Drift check | 0 on both sides in 27 samples, one a minute from the start |

**Caveat.** A two-case smoke run (`luna-070-retry-smoke`) ran on the same database first. Its
four agent turns are in the export under that run label.

Files:

- `report.md`, `report.json`: the evaluation's report, as `ap-eval` wrote it.
- `trajectories.csv`: every completed turn of every agent, 774 rows, with the run, the scenario,
  the repetition, the exception id and Nessy's `label` column.
