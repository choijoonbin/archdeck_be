ALTER TABLE archdeck.research_runs ADD COLUMN schedule_slot varchar(100);
CREATE UNIQUE INDEX research_run_schedule_slot_idx ON archdeck.research_runs(policy_id,schedule_slot) WHERE schedule_slot IS NOT NULL;
