-- ArchDeck content schema v1. PostgreSQL 17; empty, project-owned database only.
-- This is NOT a migration of the existing H2 database.
-- Apply subsequent changes through versioned migrations, never by replaying
-- this initial schema against a populated database.


CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE SCHEMA IF NOT EXISTS archdeck;
SET search_path TO archdeck, public;

-- Derived search values are rebuilt from authoritative content fields.
-- The application must escape LIKE metacharacters in user queries.
CREATE FUNCTION normalize_search_text(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT lower(regexp_replace(normalize(value, NFC), '[[:space:]]+', '', 'g'));
$$;

CREATE FUNCTION touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END;
$$;

-- Application accounts are separate from the Docker/PostgreSQL owner.
-- No default or bootstrap credentials are seeded by this schema.
CREATE TABLE app_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username varchar(32) NOT NULL UNIQUE,
  password_hash varchar(255) NOT NULL,
  display_name varchar(120) NOT NULL,
  role varchar(16) NOT NULL DEFAULT 'READER',
  enabled boolean NOT NULL DEFAULT true,
  must_change_password boolean NOT NULL DEFAULT true,
  auth_version bigint NOT NULL DEFAULT 0,
  password_changed_at timestamptz NOT NULL DEFAULT now(),
  lock_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT app_username_canonical CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,31}$'),
  -- Encoder-prefixed hashes only. Algorithm and work factor are checked by
  -- the password encoder, not inferred from a database string constraint.
  CONSTRAINT app_password_hash_encoded CHECK (password_hash ~ '^\{[A-Za-z0-9_@.-]+\}.+$'),
  CONSTRAINT app_display_name_present CHECK (btrim(display_name) <> ''),
  CONSTRAINT app_user_role_valid CHECK (role IN ('ADMIN', 'READER')),
  CONSTRAINT app_user_auth_version_valid CHECK (auth_version >= 0),
  CONSTRAINT app_user_lock_version_valid CHECK (lock_version >= 0)
);

CREATE TRIGGER app_users_touch_updated_at
BEFORE UPDATE ON app_users
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- PostgreSQL-specific Spring Session JDBC columns/indexes. The application
-- uses the immutable app_users UUID as PRINCIPAL_NAME, never the display name.
-- Source: https://raw.githubusercontent.com/spring-projects/spring-session/3.4.1/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql
CREATE TABLE spring_session (
  primary_id char(36) NOT NULL PRIMARY KEY,
  session_id char(36) NOT NULL,
  creation_time bigint NOT NULL,
  last_access_time bigint NOT NULL,
  max_inactive_interval integer NOT NULL,
  expiry_time bigint NOT NULL,
  principal_name varchar(100)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
  session_primary_id char(36) NOT NULL,
  attribute_name varchar(200) NOT NULL,
  attribute_bytes bytea NOT NULL,
  PRIMARY KEY (session_primary_id, attribute_name),
  FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

CREATE TABLE categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug varchar(96) NOT NULL UNIQUE,
  label varchar(120) NOT NULL,
  description text NOT NULL DEFAULT '',
  position integer NOT NULL DEFAULT 0,
  CONSTRAINT category_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT category_label_present CHECK (btrim(label) <> ''),
  CONSTRAINT category_position_valid CHECK (position >= 0)
);

CREATE TABLE tags (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug varchar(96) NOT NULL UNIQUE,
  label varchar(120) NOT NULL,
  CONSTRAINT tag_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT tag_label_present CHECK (btrim(label) <> '')
);

CREATE TABLE content_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind varchar(24) NOT NULL,
  slug varchar(128) NOT NULL UNIQUE,
  title varchar(255) NOT NULL,
  subtitle varchar(255) NOT NULL DEFAULT '',
  summary text NOT NULL DEFAULT '',
  body_markdown text NOT NULL DEFAULT '',
  category_id uuid NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
  status varchar(16) NOT NULL DEFAULT 'draft',
  schema_version smallint NOT NULL DEFAULT 1,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  reading_minutes smallint,
  reviewed_at date,
  published_at timestamptz,
  lock_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  search_text text GENERATED ALWAYS AS (
    normalize_search_text(title || ' ' || subtitle || ' ' || summary || ' ' || body_markdown || ' ' || payload::text)
  ) STORED,
  CONSTRAINT content_kind_valid CHECK (kind IN ('topic', 'comparison', 'glossary', 'checklist', 'learning_path')),
  CONSTRAINT content_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT content_title_present CHECK (btrim(title) <> ''),
  CONSTRAINT content_status_valid CHECK (status IN ('draft', 'published', 'archived')),
  CONSTRAINT content_schema_version_valid CHECK (schema_version >= 1),
  CONSTRAINT content_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
  -- Common metadata and relational flow data have one authoritative location.
  CONSTRAINT content_payload_no_metadata_copy CHECK (NOT (payload ?| ARRAY[
    'id', 'kind', 'slug', 'title', 'subtitle', 'summary', 'bodyMarkdown',
    'categoryId', 'status', 'schemaVersion', 'reviewedAt', 'publishedAt',
    'lockVersion', 'createdAt', 'updatedAt', 'flowNodes', 'flowEdges', 'flowSteps'
  ])),
  CONSTRAINT content_reading_minutes_valid CHECK (reading_minutes IS NULL OR reading_minutes > 0),
  CONSTRAINT content_lock_version_valid CHECK (lock_version >= 0),
  CONSTRAINT published_content_has_review CHECK (status <> 'published' OR (reviewed_at IS NOT NULL AND published_at IS NOT NULL)),
  CONSTRAINT content_identity_kind_unique UNIQUE (id, kind)
);

CREATE TRIGGER content_items_touch_updated_at
BEFORE UPDATE ON content_items
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE content_tags (
  content_id uuid NOT NULL REFERENCES content_items(id) ON DELETE CASCADE,
  tag_id uuid NOT NULL REFERENCES tags(id) ON DELETE RESTRICT,
  PRIMARY KEY (content_id, tag_id)
);

CREATE TABLE content_relations (
  source_id uuid NOT NULL REFERENCES content_items(id) ON DELETE CASCADE,
  target_id uuid NOT NULL REFERENCES content_items(id) ON DELETE RESTRICT,
  relation_type varchar(24) NOT NULL,
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY (source_id, target_id, relation_type),
  CONSTRAINT relation_no_self_reference CHECK (source_id <> target_id),
  CONSTRAINT relation_type_valid CHECK (relation_type IN ('related', 'prerequisite', 'explains', 'compares', 'verifies')),
  CONSTRAINT relation_position_valid CHECK (position >= 0)
);

CREATE TABLE sources (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  canonical_url text NOT NULL UNIQUE,
  title varchar(255) NOT NULL,
  publisher varchar(160) NOT NULL DEFAULT '',
  source_type varchar(24) NOT NULL DEFAULT 'documentation',
  published_on date,
  accessed_on date NOT NULL DEFAULT current_date,
  license_note text NOT NULL DEFAULT '',
  CONSTRAINT source_http_url CHECK (canonical_url ~* '^https?://[^[:space:]]+$'),
  CONSTRAINT source_title_present CHECK (btrim(title) <> ''),
  CONSTRAINT source_type_valid CHECK (source_type IN ('standard', 'documentation', 'article', 'video', 'repository'))
);

CREATE TABLE content_sources (
  content_id uuid NOT NULL REFERENCES content_items(id) ON DELETE CASCADE,
  source_id uuid NOT NULL REFERENCES sources(id) ON DELETE RESTRICT,
  locator text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY (content_id, source_id),
  CONSTRAINT content_source_position_valid CHECK (position >= 0)
);

CREATE TABLE content_revisions (
  content_id uuid NOT NULL REFERENCES content_items(id) ON DELETE RESTRICT,
  revision_no bigint NOT NULL,
  schema_version smallint NOT NULL,
  snapshot jsonb NOT NULL,
  change_note text NOT NULL,
  created_by uuid REFERENCES app_users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (content_id, revision_no),
  CONSTRAINT revision_number_valid CHECK (revision_no >= 1),
  CONSTRAINT revision_schema_version_valid CHECK (schema_version >= 1),
  CONSTRAINT revision_snapshot_is_object CHECK (jsonb_typeof(snapshot) = 'object'),
  CONSTRAINT revision_change_note_present CHECK (btrim(change_note) <> '')
);

CREATE TABLE flow_nodes (
  content_id uuid NOT NULL,
  content_kind varchar(24) NOT NULL DEFAULT 'topic',
  node_key varchar(64) NOT NULL,
  node_type varchar(24) NOT NULL,
  label varchar(160) NOT NULL,
  sub_label varchar(160) NOT NULL DEFAULT '',
  icon_key varchar(48) NOT NULL DEFAULT 'Server',
  position integer NOT NULL DEFAULT 0,
  x numeric(10, 2),
  y numeric(10, 2),
  PRIMARY KEY (content_id, node_key),
  FOREIGN KEY (content_id, content_kind) REFERENCES content_items(id, kind) ON DELETE CASCADE,
  CONSTRAINT flow_nodes_are_topic_owned CHECK (content_kind = 'topic'),
  CONSTRAINT flow_node_key_format CHECK (node_key ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
  CONSTRAINT flow_node_type_valid CHECK (node_type IN ('user', 'client', 'gateway', 'auth', 'server', 'database', 'external')),
  CONSTRAINT flow_node_label_present CHECK (btrim(label) <> ''),
  CONSTRAINT flow_node_position_valid CHECK (position >= 0),
  CONSTRAINT flow_node_coordinate_pair CHECK ((x IS NULL) = (y IS NULL))
);

CREATE TABLE flow_edges (
  content_id uuid NOT NULL,
  edge_key varchar(64) NOT NULL,
  source_node_key varchar(64) NOT NULL,
  target_node_key varchar(64) NOT NULL,
  label varchar(255) NOT NULL DEFAULT '',
  protocol varchar(160) NOT NULL DEFAULT '',
  edge_type varchar(16) NOT NULL DEFAULT 'request',
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY (content_id, edge_key),
  FOREIGN KEY (content_id) REFERENCES content_items(id) ON DELETE CASCADE,
  FOREIGN KEY (content_id, source_node_key) REFERENCES flow_nodes(content_id, node_key)
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (content_id, target_node_key) REFERENCES flow_nodes(content_id, node_key)
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT flow_edge_key_format CHECK (edge_key ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
  CONSTRAINT flow_edge_type_valid CHECK (edge_type IN ('request', 'response', 'event', 'internal')),
  CONSTRAINT flow_edge_position_valid CHECK (position >= 0)
);

CREATE TABLE flow_steps (
  content_id uuid NOT NULL,
  content_kind varchar(24) NOT NULL DEFAULT 'topic',
  step_key varchar(64) NOT NULL,
  position integer NOT NULL,
  title varchar(255) NOT NULL,
  action text NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  payload_example text NOT NULL DEFAULT '',
  security_note text NOT NULL DEFAULT '',
  PRIMARY KEY (content_id, step_key),
  UNIQUE (content_id, position),
  FOREIGN KEY (content_id, content_kind) REFERENCES content_items(id, kind) ON DELETE CASCADE,
  CONSTRAINT flow_steps_are_topic_owned CHECK (content_kind = 'topic'),
  CONSTRAINT flow_step_key_format CHECK (step_key ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
  CONSTRAINT flow_step_position_valid CHECK (position >= 1),
  CONSTRAINT flow_step_title_present CHECK (btrim(title) <> '')
);

CREATE TABLE flow_step_edges (
  content_id uuid NOT NULL,
  step_key varchar(64) NOT NULL,
  edge_key varchar(64) NOT NULL,
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY (content_id, step_key, edge_key),
  UNIQUE (content_id, step_key, position),
  FOREIGN KEY (content_id, step_key) REFERENCES flow_steps(content_id, step_key) ON DELETE CASCADE,
  FOREIGN KEY (content_id, edge_key) REFERENCES flow_edges(content_id, edge_key) ON DELETE CASCADE,
  CONSTRAINT step_edge_position_valid CHECK (position >= 0)
);

CREATE TABLE flow_step_nodes (
  content_id uuid NOT NULL,
  step_key varchar(64) NOT NULL,
  node_key varchar(64) NOT NULL,
  PRIMARY KEY (content_id, step_key, node_key),
  FOREIGN KEY (content_id, step_key) REFERENCES flow_steps(content_id, step_key) ON DELETE CASCADE,
  FOREIGN KEY (content_id, node_key) REFERENCES flow_nodes(content_id, node_key) ON DELETE CASCADE
);

CREATE TABLE learning_paths (
  content_id uuid PRIMARY KEY,
  content_kind varchar(24) NOT NULL DEFAULT 'learning_path',
  difficulty varchar(16) NOT NULL DEFAULT 'beginner',
  completion_rule varchar(24) NOT NULL DEFAULT 'all_required',
  FOREIGN KEY (content_id, content_kind) REFERENCES content_items(id, kind) ON DELETE CASCADE,
  CONSTRAINT learning_path_kind_valid CHECK (content_kind = 'learning_path'),
  CONSTRAINT learning_path_difficulty_valid CHECK (difficulty IN ('beginner', 'intermediate', 'advanced')),
  CONSTRAINT learning_path_completion_rule_valid CHECK (completion_rule = 'all_required')
);

CREATE TABLE learning_path_items (
  path_id uuid NOT NULL REFERENCES learning_paths(content_id) ON DELETE CASCADE,
  topic_id uuid NOT NULL,
  topic_kind varchar(24) NOT NULL DEFAULT 'topic',
  position integer NOT NULL,
  required boolean NOT NULL DEFAULT true,
  section_title varchar(160) NOT NULL DEFAULT '',
  learning_goal text NOT NULL DEFAULT '',
  PRIMARY KEY (path_id, topic_id),
  UNIQUE (path_id, position),
  FOREIGN KEY (topic_id, topic_kind) REFERENCES content_items(id, kind) ON DELETE RESTRICT,
  CONSTRAINT learning_path_item_is_topic CHECK (topic_kind = 'topic'),
  CONSTRAINT learning_path_item_position_valid CHECK (position >= 1)
);

CREATE INDEX content_browse_idx ON content_items (status, kind, category_id);
CREATE INDEX content_recently_published_idx ON content_items (published_at DESC, id) WHERE status = 'published';
CREATE INDEX content_search_trgm_idx ON content_items USING gin (search_text gin_trgm_ops);
CREATE INDEX content_tags_reverse_idx ON content_tags (tag_id, content_id);
CREATE INDEX content_relations_target_idx ON content_relations (target_id, relation_type);
CREATE INDEX content_sources_reverse_idx ON content_sources (source_id, content_id);
CREATE INDEX learning_path_items_topic_idx ON learning_path_items (topic_id, path_id);
CREATE INDEX tag_label_search_idx ON tags USING gin (normalize_search_text(label || ' ' || slug) gin_trgm_ops);
CREATE INDEX flow_node_search_idx ON flow_nodes USING gin (normalize_search_text(label || ' ' || sub_label) gin_trgm_ops);
CREATE INDEX flow_edge_search_idx ON flow_edges USING gin (normalize_search_text(label || ' ' || protocol) gin_trgm_ops);
CREATE INDEX flow_step_search_idx ON flow_steps USING gin (
  normalize_search_text(title || ' ' || action || ' ' || description || ' ' || payload_example || ' ' || security_note) gin_trgm_ops
);

-- A unified query surface without another persisted copy of content metadata.
-- Each branch has a matching trigram index. The API deduplicates content_id,
-- filters published status, then applies category/tag/type filters and paging.
CREATE VIEW content_search_documents AS
SELECT id AS content_id, search_text FROM content_items
UNION ALL
SELECT content_id, normalize_search_text(label || ' ' || sub_label) FROM flow_nodes
UNION ALL
SELECT content_id, normalize_search_text(label || ' ' || protocol) FROM flow_edges
UNION ALL
SELECT content_id, normalize_search_text(title || ' ' || action || ' ' || description || ' ' || payload_example || ' ' || security_note) FROM flow_steps
UNION ALL
SELECT ct.content_id, normalize_search_text(t.label || ' ' || t.slug)
FROM content_tags ct JOIN tags t ON t.id = ct.tag_id;

COMMENT ON SCHEMA archdeck IS 'ArchDeck shared content, minimal application accounts, JDBC sessions; private learning state stays in the browser';
COMMENT ON COLUMN content_items.payload IS 'Kind-specific content only; schema validated by API/import contract';
COMMENT ON COLUMN content_items.lock_version IS 'Optimistic locking token. API updates WHERE id AND lock_version, incrementing once per aggregate transaction';
COMMENT ON TABLE content_revisions IS 'Immutable full-aggregate snapshots written by content service in the same save transaction';
COMMENT ON VIEW content_search_documents IS 'Normalized search fragments; use parameterized escaped LIKE and enforce public published-only filtering';
