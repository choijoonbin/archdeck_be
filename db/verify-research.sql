-- Migrated V6+ research integrity. Use a dedicated verification DB; fixtures roll back.
\set ON_ERROR_STOP on
BEGIN;
SET search_path TO archdeck, public;
DO $$
DECLARE
  policy_id_value uuid := gen_random_uuid();
  run_id_value uuid := gen_random_uuid();
  affected integer;
BEGIN
  INSERT INTO research_policies(id,payload) VALUES(policy_id_value,'{}');
  INSERT INTO research_runs(id,policy_id,status,policy_snapshot)
    VALUES(run_id_value,policy_id_value,'queued','{}');
  INSERT INTO research_commands(id,run_id,command_type,payload)
    VALUES(gen_random_uuid(),run_id_value,'execute','{}');
  BEGIN
    INSERT INTO research_commands(id,run_id,command_type,payload)
      VALUES(gen_random_uuid(),run_id_value,'execute','{}');
    RAISE EXCEPTION 'FAIL: duplicate execute command accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO research_commands(id,run_id,command_type,payload)
      VALUES(gen_random_uuid(),gen_random_uuid(),'cancel','{}');
    RAISE EXCEPTION 'FAIL: command without run accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: durable command identity and run reference';

  INSERT INTO research_inbox(run_id,lease_fence,payload) VALUES(run_id_value,2,'{}');
  BEGIN
    INSERT INTO research_inbox(run_id,lease_fence,payload) VALUES(run_id_value,2,'{}');
    RAISE EXCEPTION 'FAIL: duplicate run/fence receipt accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  INSERT INTO knowledge_candidates(id,run_id,payload) VALUES(gen_random_uuid(),run_id_value,'{}');
  BEGIN
    INSERT INTO knowledge_candidates(id,run_id,payload) VALUES(gen_random_uuid(),run_id_value,'{}');
    RAISE EXCEPTION 'FAIL: duplicate candidate for same run accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: receipt and candidate uniqueness';

  UPDATE research_runs SET status='running',lease_fence=2,lease_owner='current',
    lease_until=now()+interval '30 seconds' WHERE id=run_id_value;
  UPDATE research_runs SET status='needs_review' WHERE id=run_id_value
    AND status='running' AND lease_fence=1 AND lease_owner='old' AND lease_until>now();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected<>0 THEN RAISE EXCEPTION 'FAIL: stale owner/fence accepted'; END IF;
  UPDATE research_runs SET status='cancelled',lease_until=NULL WHERE id=run_id_value;
  UPDATE research_runs SET status='needs_review' WHERE id=run_id_value
    AND status='running' AND lease_fence=2 AND lease_owner='current' AND lease_until>now();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected<>0 THEN RAISE EXCEPTION 'FAIL: cancelled execution accepted'; END IF;
  RAISE NOTICE 'PASS: owner/fence/cancel acceptance predicates';
END;
$$;
ROLLBACK;
\echo 'ArchDeck V6 research integrity passed; all fixtures rolled back.'
