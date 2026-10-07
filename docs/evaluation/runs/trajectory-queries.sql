-- Trajectory analysis of a run. Load the CSV export first:
--   create table traj (run text, scenario text, repetition int, exception_id uuid, agent_type text,
--     agent_id uuid, turn_id bigint, ending_seq bigint, arrived_at timestamptz, started_at timestamptz,
--     ended_at timestamptz, outcome text, round_count int, tool_call_count int, tool_success_count int,
--     tool_failure_count int, tool_denied_count int, inference_call_count int, inference_retry_count int,
--     trajectory_version smallint, trajectory_hash char(64), path text, trajectory jsonb);
--   \copy traj from '20261007-133459-luna-060-trajectories.csv' with csv header
-- Against the live desk database, replace `traj` with nessy_agent_turn joined to ap_case on
-- agent_id, and take the scenario from the eval log's "scenario #n: exception <id>" lines.
-- Reader turns (agent_type = 'reply-reader') carry no case, so their run and scenario are empty.

-- 1. How many behaviors, and how concentrated. Half the trajectories being singletons is normal.
select agent_type, count(*) turns, count(distinct trajectory_hash) trajectories,
       count(*) filter (where outcome <> 'ANSWERED') not_answered, sum(inference_retry_count) retries
from traj where coalesce(run, '') <> 'luna-060-smoke' group by 1;

-- 2. The head: the paths that cover most turns.
select count(*) n, outcome, path from traj
where run = 'luna-060' and agent_type = 'ap-exception-resolver'
group by trajectory_hash, outcome, path order by n desc limit 10;

-- 3. Determinism per scenario: trajectories per turn. A high ratio means the model is not settled.
select scenario, count(*) turns, count(distinct trajectory_hash) trajectories,
       round(count(distinct trajectory_hash)::numeric / count(*), 2) ratio,
       sum(tool_failure_count) failures, sum(tool_denied_count) denials
from traj where run = 'luna-060' and agent_type = 'ap-exception-resolver'
group by 1 order by ratio desc;

-- 4. Tool outcomes that were not SUCCESS, by scenario and tool. FAILED includes the desk's own
--    refusals (the mail limit, no PO), because a Nessy tool can only answer success or failure.
select scenario, e->>'tool' tool, e->>'outcome' outcome, count(*)
from traj, jsonb_array_elements(trajectory->'rounds') r, jsonb_array_elements(r) e
where run = 'luna-060' and agent_type = 'ap-exception-resolver' and e->>'outcome' <> 'SUCCESS'
group by 1, 2, 3 order by 4 desc;

-- 5. The attack set difference: trajectories the attacks produced that no benign no-PO case did.
with benign as (select distinct trajectory_hash from traj
                where run = 'luna-060'
                  and scenario in ('no-po', 'silent-vendor', 'vendor-names-the-po', 'bank-change-by-mail'))
select scenario, count(*) n, path from traj
where run = 'luna-060' and scenario like 'injected-reply%'
  and trajectory_hash not in (select trajectory_hash from benign)
group by scenario, trajectory_hash, path order by 1, 2 desc;

-- 6. Find turns by what they did: containment on the readable trajectory.
select scenario, repetition, exception_id, turn_id, path from traj
where run = 'luna-060'
  and trajectory @> '{"rounds":[[{"tool":"get_invoice","outcome":"FAILED"}]]}'
order by 1, 2;

-- 7. Control coverage: where the policy refused a proposal.
select scenario, count(*) turns from traj
where run = 'luna-060'
  and trajectory @> '{"rounds":[[{"tool":"propose_resolution","outcome":"DENIED"}]]}'
group by 1;

-- 8. Behavior diff between two runs: paths new in the later run.
-- select distinct path from traj where run = 'B' except select path from traj where run = 'A';
