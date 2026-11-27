ALTER TABLE archdeck.content_items DROP CONSTRAINT content_kind_valid;
ALTER TABLE archdeck.content_items ADD CONSTRAINT content_kind_valid CHECK(kind IN ('topic','comparison','glossary','checklist','learning_path','insight'));
CREATE TABLE archdeck.research_policies(id uuid PRIMARY KEY,payload jsonb NOT NULL,version bigint NOT NULL DEFAULT 1);
CREATE TABLE archdeck.research_runs(id uuid PRIMARY KEY,policy_id uuid REFERENCES archdeck.research_policies(id),status varchar(30) NOT NULL,result jsonb,error varchar(120),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE archdeck.knowledge_candidates(id uuid PRIMARY KEY,run_id uuid REFERENCES archdeck.research_runs(id),payload jsonb NOT NULL,status varchar(30) NOT NULL DEFAULT 'needs_review',version bigint NOT NULL DEFAULT 1,published_content_id uuid REFERENCES archdeck.content_items(id),reviewer_id uuid REFERENCES archdeck.app_users(id),review_reason text,created_at timestamptz NOT NULL DEFAULT now());

ALTER TABLE archdeck.flow_steps ADD COLUMN actor text NOT NULL DEFAULT '';
ALTER TABLE archdeck.flow_steps ADD COLUMN target text NOT NULL DEFAULT '';
ALTER TABLE archdeck.flow_steps ADD COLUMN protocol text NOT NULL DEFAULT '';
