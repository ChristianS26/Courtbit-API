-- Migration: Preserve ranking data when tournaments are deleted
-- Adds tournament_name to ranking_events so data survives tournament deletion

-- 1. Add tournament_name column
ALTER TABLE ranking_events ADD COLUMN IF NOT EXISTS tournament_name TEXT;

-- 2. Backfill existing rows with tournament names
UPDATE ranking_events
SET tournament_name = t.name
FROM tournaments t
WHERE ranking_events.tournament_id = t.id
  AND ranking_events.tournament_name IS NULL;

-- 3. Make tournament_id nullable with ON DELETE SET NULL
-- First drop the existing FK constraint (name may vary, use a DO block to find it)
DO $$
DECLARE
  fk_name TEXT;
BEGIN
  SELECT tc.constraint_name INTO fk_name
  FROM information_schema.table_constraints tc
  JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
  WHERE tc.table_name = 'ranking_events'
    AND tc.constraint_type = 'FOREIGN KEY'
    AND kcu.column_name = 'tournament_id'
  LIMIT 1;

  IF fk_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE ranking_events DROP CONSTRAINT %I', fk_name);
  END IF;
END $$;

-- Re-add FK with ON DELETE SET NULL
ALTER TABLE ranking_events
  ADD CONSTRAINT ranking_events_tournament_id_fkey
  FOREIGN KEY (tournament_id) REFERENCES tournaments(id)
  ON DELETE SET NULL;

-- 4. Update add_ranking_event_and_update RPC to accept and store tournament_name
CREATE OR REPLACE FUNCTION add_ranking_event_and_update(
  p_user_id UUID DEFAULT NULL,
  p_season TEXT DEFAULT NULL,
  p_category_id INT DEFAULT NULL,
  p_points INT DEFAULT 0,
  p_tournament_id UUID DEFAULT NULL,
  p_position TEXT DEFAULT NULL,
  p_team_result_id UUID DEFAULT NULL,
  p_team_member_id TEXT DEFAULT NULL,
  p_tournament_name TEXT DEFAULT NULL
) RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_event_id UUID;
  v_tournament_name TEXT;
BEGIN
  -- Resolve tournament name: use provided name, or look it up
  v_tournament_name := p_tournament_name;
  IF v_tournament_name IS NULL AND p_tournament_id IS NOT NULL THEN
    SELECT name INTO v_tournament_name FROM tournaments WHERE id = p_tournament_id;
  END IF;

  -- Insert ranking event
  INSERT INTO ranking_events (user_id, team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name)
  VALUES (p_user_id, p_team_member_id, p_tournament_id, p_category_id, p_season, p_points, p_position, p_team_result_id, v_tournament_name)
  RETURNING id INTO v_event_id;

  -- Upsert ranking total (only for user-based entries)
  IF p_user_id IS NOT NULL THEN
    INSERT INTO ranking (user_id, category_id, season, total_points)
    VALUES (p_user_id, p_category_id, p_season, p_points)
    ON CONFLICT (user_id, category_id, season)
    DO UPDATE SET
      total_points = ranking.total_points + EXCLUDED.total_points,
      updated_at = NOW();
  END IF;

  RETURN v_event_id;
END;
$$;

-- 5. Update batch_add_ranking_events RPC to also store tournament_name
CREATE OR REPLACE FUNCTION batch_add_ranking_events(
  p_tournament_id UUID,
  p_category_id INT,
  p_season TEXT,
  p_entries JSONB,
  p_tournament_name TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  entry JSONB;
  v_user_id UUID;
  v_team_member_id TEXT;
  v_points INT;
  v_position TEXT;
  v_team_result_id UUID;
  v_event_id UUID;
  v_results JSONB := '[]'::JSONB;
  v_inserted_count INT := 0;
  v_skipped_count INT := 0;
  v_tournament_name TEXT;
BEGIN
  -- Resolve tournament name
  v_tournament_name := p_tournament_name;
  IF v_tournament_name IS NULL AND p_tournament_id IS NOT NULL THEN
    SELECT name INTO v_tournament_name FROM tournaments WHERE id = p_tournament_id;
  END IF;

  FOR entry IN SELECT * FROM jsonb_array_elements(p_entries)
  LOOP
    v_user_id := (entry ->> 'user_id')::UUID;
    v_team_member_id := entry ->> 'team_member_id';
    v_points := (entry ->> 'points')::INT;
    v_position := entry ->> 'position';
    v_team_result_id := (entry ->> 'team_result_id')::UUID;

    INSERT INTO ranking_events (user_id, team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name)
    VALUES (v_user_id, v_team_member_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id, v_tournament_name)
    ON CONFLICT DO NOTHING
    RETURNING id INTO v_event_id;

    IF v_event_id IS NOT NULL THEN
      v_inserted_count := v_inserted_count + 1;

      IF v_user_id IS NOT NULL THEN
        INSERT INTO ranking (user_id, category_id, season, total_points)
        VALUES (v_user_id, p_category_id, p_season, v_points)
        ON CONFLICT (user_id, category_id, season)
        DO UPDATE SET
          total_points = ranking.total_points + EXCLUDED.total_points,
          updated_at = NOW();
      END IF;

      v_results := v_results || jsonb_build_object('event_id', v_event_id, 'status', 'inserted');
    ELSE
      v_skipped_count := v_skipped_count + 1;
      v_results := v_results || jsonb_build_object('status', 'skipped_duplicate');
    END IF;
  END LOOP;

  RETURN jsonb_build_object(
    'inserted', v_inserted_count,
    'skipped', v_skipped_count,
    'results', v_results
  );
END;
$$;
