-- Migration: Support manual (unregistered) players in ranking table
-- Previously, only registered users (with user_id) could appear in rankings.
-- Now team_member_id entries also get ranking rows with player_name for display.

-- 1. Make user_id nullable (was NOT NULL)
ALTER TABLE ranking ALTER COLUMN user_id DROP NOT NULL;

-- 2. Add team_member_id and player_name columns
ALTER TABLE ranking ADD COLUMN IF NOT EXISTS team_member_id TEXT;
ALTER TABLE ranking ADD COLUMN IF NOT EXISTS player_name TEXT;

-- 3. Replace old unique constraint with partial unique indexes
-- Drop the constraint first (it backs the unique index)
ALTER TABLE ranking DROP CONSTRAINT IF EXISTS ranking_user_category_season_unique;

-- Partial unique index for registered users
CREATE UNIQUE INDEX ranking_user_category_season_unique
  ON ranking (user_id, category_id, season)
  WHERE user_id IS NOT NULL;

-- Partial unique index for manual players
CREATE UNIQUE INDEX ranking_member_category_season_unique
  ON ranking (team_member_id, category_id, season)
  WHERE team_member_id IS NOT NULL;

-- 4. Drop old 4-param overload of batch_add_ranking_events (causes ambiguity)
DROP FUNCTION IF EXISTS batch_add_ranking_events(UUID, INT, TEXT, JSONB);

-- 5. Update batch_add_ranking_events RPC to also upsert ranking for team_member_id entries
-- NOTE: Partial indexes require WHERE clause in ON CONFLICT targets
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
  v_player_name TEXT;
  v_event_id UUID;
  v_results JSONB := '[]'::JSONB;
  v_inserted_count INT := 0;
  v_skipped_count INT := 0;
  v_tournament_name TEXT;
  v_organizer_id UUID;
BEGIN
  v_tournament_name := p_tournament_name;
  IF p_tournament_id IS NOT NULL THEN
    SELECT name, organizer_id INTO v_tournament_name, v_organizer_id
    FROM tournaments WHERE id = p_tournament_id;
    IF p_tournament_name IS NOT NULL THEN
      v_tournament_name := p_tournament_name;
    END IF;
  END IF;

  FOR entry IN SELECT * FROM jsonb_array_elements(p_entries)
  LOOP
    v_user_id := (entry ->> 'user_id')::UUID;
    v_team_member_id := entry ->> 'team_member_id';
    v_points := (entry ->> 'points')::INT;
    v_position := entry ->> 'position';
    v_team_result_id := (entry ->> 'team_result_id')::UUID;
    v_player_name := entry ->> 'player_name';

    -- Use separate INSERT paths to match partial index WHERE clauses
    v_event_id := NULL;

    IF v_user_id IS NOT NULL THEN
      INSERT INTO ranking_events (user_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name)
      VALUES (v_user_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id, v_tournament_name)
      ON CONFLICT (user_id, tournament_id, category_id) WHERE user_id IS NOT NULL AND tournament_id IS NOT NULL
      DO NOTHING
      RETURNING id INTO v_event_id;
    ELSIF v_team_member_id IS NOT NULL THEN
      INSERT INTO ranking_events (team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name)
      VALUES (v_team_member_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id, v_tournament_name)
      ON CONFLICT (team_member_id, tournament_id, category_id) WHERE team_member_id IS NOT NULL AND tournament_id IS NOT NULL
      DO NOTHING
      RETURNING id INTO v_event_id;
    END IF;

    IF v_event_id IS NOT NULL THEN
      v_inserted_count := v_inserted_count + 1;

      IF v_user_id IS NOT NULL THEN
        INSERT INTO ranking (user_id, category_id, season, total_points, organizer_id)
        VALUES (v_user_id, p_category_id, p_season, v_points, v_organizer_id)
        ON CONFLICT (user_id, category_id, season) WHERE user_id IS NOT NULL
        DO UPDATE SET
          total_points = ranking.total_points + EXCLUDED.total_points,
          organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
          updated_at = NOW();
      ELSIF v_team_member_id IS NOT NULL THEN
        INSERT INTO ranking (team_member_id, player_name, category_id, season, total_points, organizer_id)
        VALUES (v_team_member_id, v_player_name, p_category_id, p_season, v_points, v_organizer_id)
        ON CONFLICT (team_member_id, category_id, season) WHERE team_member_id IS NOT NULL
        DO UPDATE SET
          total_points = ranking.total_points + EXCLUDED.total_points,
          player_name = COALESCE(EXCLUDED.player_name, ranking.player_name),
          organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
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

-- 5. Also update add_ranking_event_and_update for single-event adds
CREATE OR REPLACE FUNCTION add_ranking_event_and_update(
  p_user_id UUID DEFAULT NULL,
  p_season TEXT DEFAULT NULL,
  p_category_id INT DEFAULT NULL,
  p_points INT DEFAULT 0,
  p_tournament_id UUID DEFAULT NULL,
  p_position TEXT DEFAULT NULL,
  p_team_result_id UUID DEFAULT NULL,
  p_team_member_id TEXT DEFAULT NULL,
  p_tournament_name TEXT DEFAULT NULL,
  p_player_name TEXT DEFAULT NULL
) RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_event_id UUID;
  v_tournament_name TEXT;
  v_organizer_id UUID;
BEGIN
  v_tournament_name := p_tournament_name;
  IF p_tournament_id IS NOT NULL THEN
    SELECT name, organizer_id INTO v_tournament_name, v_organizer_id
    FROM tournaments WHERE id = p_tournament_id;
    IF p_tournament_name IS NOT NULL THEN
      v_tournament_name := p_tournament_name;
    END IF;
  END IF;

  INSERT INTO ranking_events (user_id, team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name)
  VALUES (p_user_id, p_team_member_id, p_tournament_id, p_category_id, p_season, p_points, p_position, p_team_result_id, v_tournament_name)
  RETURNING id INTO v_event_id;

  IF p_user_id IS NOT NULL THEN
    INSERT INTO ranking (user_id, category_id, season, total_points, organizer_id)
    VALUES (p_user_id, p_category_id, p_season, p_points, v_organizer_id)
    ON CONFLICT (user_id, category_id, season) WHERE user_id IS NOT NULL
    DO UPDATE SET
      total_points = ranking.total_points + EXCLUDED.total_points,
      organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
      updated_at = NOW();
  ELSIF p_team_member_id IS NOT NULL THEN
    INSERT INTO ranking (team_member_id, player_name, category_id, season, total_points, organizer_id)
    VALUES (p_team_member_id, p_player_name, p_category_id, p_season, p_points, v_organizer_id)
    ON CONFLICT (team_member_id, category_id, season) WHERE team_member_id IS NOT NULL
    DO UPDATE SET
      total_points = ranking.total_points + EXCLUDED.total_points,
      player_name = COALESCE(EXCLUDED.player_name, ranking.player_name),
      organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
      updated_at = NOW();
  END IF;

  RETURN v_event_id;
END;
$$;
