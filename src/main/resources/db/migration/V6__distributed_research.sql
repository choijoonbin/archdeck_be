ALTER TABLE archdeck.research_runs ADD COLUMN lease_owner varchar(100);
ALTER TABLE archdeck.research_runs ADD COLUMN lease_until timestamptz;
ALTER TABLE archdeck.research_runs ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE archdeck.research_runs ADD COLUMN attempt integer NOT NULL DEFAULT 0;
CREATE INDEX research_claim ON archdeck.research_runs(status,lease_until,created_at);
CREATE TABLE archdeck.research_commands (
 id uuid PRIMARY KEY,run_id uuid NOT NULL REFERENCES archdeck.research_runs(id),
 command_type varchar(20) NOT NULL CHECK(command_type IN ('execute','cancel')),
 payload jsonb NOT NULL,state varchar(20) NOT NULL DEFAULT 'pending',
 available_at timestamptz NOT NULL DEFAULT now(),lease_until timestamptz,
 attempts integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(run_id,command_type)
);
CREATE TABLE archdeck.research_inbox (
 run_id uuid NOT NULL REFERENCES archdeck.research_runs(id),lease_fence bigint NOT NULL,
 payload jsonb NOT NULL,received_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(run_id,lease_fence)
);
CREATE UNIQUE INDEX candidate_run_once ON archdeck.knowledge_candidates(run_id);
ALTER TABLE archdeck.flow_nodes ADD COLUMN trust_zone text NOT NULL DEFAULT '';
-- Recover historical queued/running work into durable commands. Never fail another worker on startup.
INSERT INTO archdeck.research_commands(id,run_id,command_type,payload)
 SELECT id,id,'execute',jsonb_build_object('legacyPolicy',policy_snapshot)
 FROM archdeck.research_runs WHERE status IN ('queued','running') AND policy_snapshot IS NOT NULL;
ALTER TABLE archdeck.flow_edges ADD COLUMN trust_boundary text NOT NULL DEFAULT '';
ALTER TABLE archdeck.flow_edges DROP CONSTRAINT flow_edge_type_valid;
ALTER TABLE archdeck.flow_edges ADD CONSTRAINT flow_edge_type_valid CHECK(edge_type IN ('request','response','event','internal','error'));
