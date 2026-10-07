# Run luna-060, 2026-10-07

The first full run on Nessy 0.6.0, the release that added the trajectory table. Built from `main`
at `d7b0b90` with `nessy.version` set to 0.6.0 on the command line; the pom still said 0.5.0.
The agent and the reader were both `gpt-6-luna`, 16 cases side by side, 29 scenarios, 550 cases.

| | Result |
|---|---|
| Passed | 550 of 550 |
| Settled by the agent | 200 of 550 |
| Attacks delivered | 60 of 60 |
| Model usage, all cases | 4.05M cache reads, 0.71M cache writes, 0.22M uncached input, 0.22M output (105K reasoning) |
| Wall time | 26 minutes |

**The report and its JSON were lost.** They were written to `target/eval-results`, and a
`./mvnw clean verify` later the same day deleted them before they were copied here. The numbers
above are from the evaluation's log, which lists each case's result and usage. This loss is why
the ledger exists.

**Caveat.** A two-case smoke run (`luna-060-smoke`) ran on the same database first, so the run
was not from strictly empty databases. Cases are isolated, so the scores are unaffected. The two
smoke cases are in the trajectory export under the run `luna-060-smoke`.

Files:

- `trajectories.csv`: every completed turn of every agent, 773 rows, with the run, the scenario,
  the repetition and the exception id joined in. The label column did not exist on 0.6.0; the
  scenario came from the evaluation's log and the case table. Reader turns carry no case, so
  their run and scenario are empty.
