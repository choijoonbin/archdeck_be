-- Read/write integrity checks in one transaction. All fixtures are rolled back.
-- Run against the project-owned PostgreSQL created by compose.yaml.
\set ON_ERROR_STOP on
BEGIN;
SET search_path TO archdeck, public;
SET CONSTRAINTS ALL IMMEDIATE;

DO $$
DECLARE
  category_id_value uuid;
  tag_id_value uuid;
  admin_id_value uuid := gen_random_uuid();
  topic_a uuid := gen_random_uuid();
  topic_b uuid := gen_random_uuid();
  glossary_id_value uuid := gen_random_uuid();
  comparison_id_value uuid := gen_random_uuid();
  path_id_value uuid := gen_random_uuid();
  source_id_value uuid := gen_random_uuid();
  session_key uuid := gen_random_uuid();
  fixture_suffix text := left(replace(gen_random_uuid()::text, '-', ''), 12);
  affected integer;
  actual_count integer;
BEGIN
  SELECT count(*) INTO actual_count FROM information_schema.tables
  WHERE table_schema = 'archdeck' AND table_type = 'BASE TABLE';
  IF actual_count < 18 THEN
    RAISE EXCEPTION 'Expected at least the 18 baseline tables, got %', actual_count;
  END IF;
  RAISE NOTICE 'PASS: 18-table baseline is present (additional migrated tables allowed)';

  SELECT id INTO STRICT category_id_value FROM categories WHERE slug = 'identity-and-access';
  SELECT id INTO STRICT tag_id_value FROM tags WHERE slug = 'oidc';

  -- An intentionally non-login-capable encoded-hash fixture, not a real password.
  INSERT INTO app_users(id, username, password_hash, display_name, role)
  VALUES (admin_id_value, 'verify-' || fixture_suffix, '{argon2}schema-test-only', '검증 관리자', 'ADMIN');
  BEGIN
    INSERT INTO app_users(username, password_hash, display_name)
    VALUES ('bad-' || fixture_suffix, 'plaintext-is-not-allowed', 'Invalid');
    RAISE EXCEPTION 'FAIL: plaintext password was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO app_users(username, password_hash, display_name, role)
    VALUES ('role-' || fixture_suffix, '{argon2}schema-test-only', 'Invalid', 'OWNER');
    RAISE EXCEPTION 'FAIL: unsupported role was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO app_users(username, password_hash, display_name)
    VALUES ('verify-' || fixture_suffix, '{argon2}schema-test-only', 'Duplicate');
    RAISE EXCEPTION 'FAIL: duplicate username was accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: account hash marker, role and username constraints';

  INSERT INTO content_items(id, kind, slug, title, summary, category_id, status, reviewed_at, published_at)
  VALUES
    (topic_a, 'topic', 'verify-topic-a-' || fixture_suffix, '인증 흐름 비교', 'OIDC 브라우저 인증', category_id_value, 'published', current_date, now()),
    (topic_b, 'topic', 'verify-topic-b-' || fixture_suffix, '흐름 없는 개념 문서', '', category_id_value, 'draft', NULL, NULL),
    (glossary_id_value, 'glossary', 'verify-glossary-' || fixture_suffix, '검증 용어', '', category_id_value, 'draft', NULL, NULL),
    (comparison_id_value, 'comparison', 'verify-comparison-' || fixture_suffix, '검증 비교', '', category_id_value, 'draft', NULL, NULL),
    (path_id_value, 'learning_path', 'verify-path-' || fixture_suffix, '인증 학습 경로', '', category_id_value, 'draft', NULL, NULL);

  BEGIN
    INSERT INTO content_items(kind, slug, title, category_id)
    VALUES ('glossary', 'verify-topic-a-' || fixture_suffix, 'Duplicate slug', category_id_value);
    RAISE EXCEPTION 'FAIL: duplicate content slug was accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO content_items(kind, slug, title, category_id, payload)
    VALUES ('topic', 'verify-array-' || fixture_suffix, 'Invalid', category_id_value, '[]'::jsonb);
    RAISE EXCEPTION 'FAIL: non-object payload was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO content_items(kind, slug, title, category_id, payload)
    VALUES ('topic', 'verify-meta-' || fixture_suffix, 'Invalid', category_id_value, '{"title":"duplicate metadata"}'::jsonb);
    RAISE EXCEPTION 'FAIL: duplicate metadata was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO content_items(kind, slug, title, category_id, status)
    VALUES ('topic', 'verify-review-' || fixture_suffix, 'Invalid', category_id_value, 'published');
    RAISE EXCEPTION 'FAIL: publishing without review metadata was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: content identity, payload and publication constraints';

  INSERT INTO flow_nodes(content_id, node_key, node_type, label, position)
  VALUES (topic_a, 'client', 'client', '브라우저', 0),
    (topic_a, 'server', 'server', '검증 서버', 1),
    (topic_b, 'other', 'server', '다른 토픽 전용', 0);
  INSERT INTO flow_edges(content_id, edge_key, source_node_key, target_node_key, label)
  VALUES (topic_a, 'request', 'client', 'server', '인가 코드 교환');
  INSERT INTO flow_steps(content_id, step_key, position, title)
  VALUES (topic_a, 'step1', 1, '단계별 토큰 교환');
  INSERT INTO flow_step_edges(content_id, step_key, edge_key)
  VALUES (topic_a, 'step1', 'request');
  INSERT INTO flow_step_nodes(content_id, step_key, node_key)
  VALUES (topic_a, 'step1', 'client'), (topic_a, 'step1', 'server');
  BEGIN
    INSERT INTO flow_edges(content_id, edge_key, source_node_key, target_node_key)
    VALUES (topic_a, 'crossTopic', 'client', 'other');
    RAISE EXCEPTION 'FAIL: cross-topic graph reference was accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO flow_nodes(content_id, node_key, node_type, label)
    VALUES (comparison_id_value, 'invalidNode', 'server', 'Not topic-owned');
    RAISE EXCEPTION 'FAIL: a comparison owned graph nodes';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO flow_step_edges(content_id, step_key, edge_key, position)
    VALUES (topic_a, 'step1', 'missing', 1);
    RAISE EXCEPTION 'FAIL: missing step edge was accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: topic-owned graph and step referential integrity';

  INSERT INTO learning_paths(content_id) VALUES (path_id_value);
  INSERT INTO learning_path_items(path_id, topic_id, position)
  VALUES (path_id_value, topic_a, 1), (path_id_value, topic_b, 2);
  BEGIN
    INSERT INTO learning_path_items(path_id, topic_id, position)
    VALUES (path_id_value, glossary_id_value, 3);
    RAISE EXCEPTION 'FAIL: a glossary item was used as a learning-path topic';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  BEGIN
    UPDATE learning_path_items SET position = 1 WHERE path_id = path_id_value AND topic_id = topic_b;
    RAISE EXCEPTION 'FAIL: duplicate learning-path order was accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: learning-path type and order constraints';

  INSERT INTO content_tags(content_id, tag_id) VALUES (topic_a, tag_id_value);
  INSERT INTO content_relations(source_id, target_id, relation_type)
  VALUES (topic_a, topic_b, 'related');
  INSERT INTO sources(id, canonical_url, title, publisher, source_type)
  VALUES (source_id_value, 'https://example.com/archdeck-verification/' || fixture_suffix, '검증용 출처', 'Fixture', 'documentation');
  INSERT INTO content_sources(content_id, source_id) VALUES (topic_a, source_id_value);
  INSERT INTO content_revisions(content_id, revision_no, schema_version, snapshot, change_note, created_by)
  VALUES (topic_a, 1, 1, jsonb_build_object('id', topic_a, 'title', '인증 흐름 비교'), '검증용 전체 aggregate snapshot fixture', admin_id_value);
  BEGIN
    DELETE FROM content_items WHERE id = topic_b;
    RAISE EXCEPTION 'FAIL: referenced topic was deleted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  BEGIN
    DELETE FROM app_users WHERE id = admin_id_value;
    RAISE EXCEPTION 'FAIL: revision author was deleted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: source, relation, revision-author and deletion policies';

  UPDATE content_items SET lock_version = lock_version + 1
  WHERE id = topic_a AND lock_version = 0;
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN RAISE EXCEPTION 'FAIL: first optimistic update did not apply'; END IF;
  UPDATE content_items SET lock_version = lock_version + 1
  WHERE id = topic_a AND lock_version = 0;
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 0 THEN RAISE EXCEPTION 'FAIL: stale optimistic update applied'; END IF;
  RAISE NOTICE 'PASS: stale-version update is rejected by the intended SQL predicate';

  IF normalize_search_text(' 인증  흐름 ') <> '인증흐름' THEN
    RAISE EXCEPTION 'FAIL: Korean whitespace normalization';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM content_search_documents WHERE content_id = topic_a AND search_text LIKE '%인증흐름%')
    OR NOT EXISTS (SELECT 1 FROM content_search_documents WHERE content_id = topic_a AND search_text LIKE '%브라우저%')
    OR NOT EXISTS (SELECT 1 FROM content_search_documents WHERE content_id = topic_a AND search_text LIKE '%인가코드%')
    OR NOT EXISTS (SELECT 1 FROM content_search_documents WHERE content_id = topic_a AND search_text LIKE '%단계별%')
    OR NOT EXISTS (SELECT 1 FROM content_search_documents WHERE content_id = topic_a AND search_text LIKE '%oidc%') THEN
    RAISE EXCEPTION 'FAIL: unified Korean, graph, step or tag search';
  END IF;
  RAISE NOTICE 'PASS: Korean partial search across content, nodes, edges, steps and tags';

  INSERT INTO spring_session(primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name)
  VALUES (session_key::text, gen_random_uuid()::text, 1, 1, 1800, 1800001, admin_id_value::text);
  INSERT INTO spring_session_attributes(session_primary_id, attribute_name, attribute_bytes)
  VALUES (session_key::text, 'verification-fixture', convert_to('not-a-live-session', 'UTF8'));
  DELETE FROM spring_session WHERE primary_id = session_key::text;
  IF EXISTS (SELECT 1 FROM spring_session_attributes WHERE session_primary_id = session_key::text) THEN
    RAISE EXCEPTION 'FAIL: session attribute cascade';
  END IF;
  RAISE NOTICE 'PASS: JDBC session and attribute lifecycle constraints';
END;
$$;

ROLLBACK;
\echo 'ArchDeck database integrity checks passed; all test fixtures were rolled back.'
