-- Migration: Add player_email and player_phone to ranking table for cascade lookup
-- This enables matching manual players by contact info when looking up ranking points

-- 1. Add columns to ranking table
ALTER TABLE ranking ADD COLUMN IF NOT EXISTS player_email TEXT;
ALTER TABLE ranking ADD COLUMN IF NOT EXISTS player_phone TEXT;

-- 2. Add columns to ranking_events table (for audit trail)
ALTER TABLE ranking_events ADD COLUMN IF NOT EXISTS player_email TEXT;
ALTER TABLE ranking_events ADD COLUMN IF NOT EXISTS player_phone TEXT;

-- 3. Create partial indexes for email/phone lookup on ranking
CREATE INDEX IF NOT EXISTS idx_ranking_email_cat_season
  ON ranking (player_email, category_id, season) WHERE player_email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ranking_phone_cat_season
  ON ranking (player_phone, category_id, season) WHERE player_phone IS NOT NULL;

-- 4. Update batch_add_ranking_events RPC to accept and store email/phone
DROP FUNCTION IF EXISTS batch_add_ranking_events(UUID, INT, TEXT, JSONB, TEXT);

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
  v_player_email TEXT;
  v_player_phone TEXT;
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
    v_player_email := entry ->> 'player_email';
    v_player_phone := entry ->> 'player_phone';

    v_event_id := NULL;

    IF v_user_id IS NOT NULL THEN
      INSERT INTO ranking_events (user_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name, player_email, player_phone)
      VALUES (v_user_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id, v_tournament_name, v_player_email, v_player_phone)
      ON CONFLICT (user_id, tournament_id, category_id) WHERE user_id IS NOT NULL AND tournament_id IS NOT NULL
      DO NOTHING
      RETURNING id INTO v_event_id;
    ELSIF v_team_member_id IS NOT NULL THEN
      INSERT INTO ranking_events (team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id, tournament_name, player_email, player_phone)
      VALUES (v_team_member_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id, v_tournament_name, v_player_email, v_player_phone)
      ON CONFLICT (team_member_id, tournament_id, category_id) WHERE team_member_id IS NOT NULL AND tournament_id IS NOT NULL
      DO NOTHING
      RETURNING id INTO v_event_id;
    END IF;

    IF v_event_id IS NOT NULL THEN
      v_inserted_count := v_inserted_count + 1;

      IF v_user_id IS NOT NULL THEN
        INSERT INTO ranking (user_id, category_id, season, total_points, organizer_id, player_email, player_phone)
        VALUES (v_user_id, p_category_id, p_season, v_points, v_organizer_id, v_player_email, v_player_phone)
        ON CONFLICT (user_id, category_id, season) WHERE user_id IS NOT NULL
        DO UPDATE SET
          total_points = ranking.total_points + EXCLUDED.total_points,
          organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
          player_email = COALESCE(EXCLUDED.player_email, ranking.player_email),
          player_phone = COALESCE(EXCLUDED.player_phone, ranking.player_phone),
          updated_at = NOW();
      ELSIF v_team_member_id IS NOT NULL THEN
        INSERT INTO ranking (team_member_id, player_name, category_id, season, total_points, organizer_id, player_email, player_phone)
        VALUES (v_team_member_id, v_player_name, p_category_id, p_season, v_points, v_organizer_id, v_player_email, v_player_phone)
        ON CONFLICT (team_member_id, category_id, season) WHERE team_member_id IS NOT NULL
        DO UPDATE SET
          total_points = ranking.total_points + EXCLUDED.total_points,
          player_name = COALESCE(EXCLUDED.player_name, ranking.player_name),
          organizer_id = COALESCE(EXCLUDED.organizer_id, ranking.organizer_id),
          player_email = COALESCE(EXCLUDED.player_email, ranking.player_email),
          player_phone = COALESCE(EXCLUDED.player_phone, ranking.player_phone),
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
