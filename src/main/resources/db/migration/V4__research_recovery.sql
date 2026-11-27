ALTER TABLE archdeck.research_runs ADD COLUMN policy_snapshot jsonb;
ALTER TABLE archdeck.research_runs ADD COLUMN lease_fence bigint NOT NULL DEFAULT 1;
ALTER TABLE archdeck.knowledge_candidates ADD COLUMN publication_key varchar(160) UNIQUE;
