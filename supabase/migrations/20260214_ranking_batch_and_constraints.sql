-- Migration: Add unique constraints and batch RPC for ranking events
-- Prevents duplicate point assignments and provides atomic batch processing

-- 1. Partial UNIQUE indexes to prevent duplicate ranking events per tournament+category
CREATE UNIQUE INDEX IF NOT EXISTS idx_ranking_events_user_unique
  ON ranking_events (user_id, tournament_id, category_id)
  WHERE user_id IS NOT NULL AND tournament_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_ranking_events_member_unique
  ON ranking_events (team_member_id, tournament_id, category_id)
  WHERE team_member_id IS NOT NULL AND tournament_id IS NOT NULL;

-- 2. Batch RPC: atomically insert multiple ranking events and update ranking totals
CREATE OR REPLACE FUNCTION batch_add_ranking_events(
  p_tournament_id UUID,
  p_category_id INT,
  p_season TEXT,
  p_entries JSONB
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
  v_inserted BOOLEAN;
  v_results JSONB := '[]'::JSONB;
  v_inserted_count INT := 0;
  v_skipped_count INT := 0;
BEGIN
  FOR entry IN SELECT * FROM jsonb_array_elements(p_entries)
  LOOP
    v_user_id := (entry ->> 'user_id')::UUID;
    v_team_member_id := entry ->> 'team_member_id';
    v_points := (entry ->> 'points')::INT;
    v_position := entry ->> 'position';
    v_team_result_id := (entry ->> 'team_result_id')::UUID;
    v_inserted := FALSE;

    -- Attempt insert with ON CONFLICT DO NOTHING
    INSERT INTO ranking_events (user_id, team_member_id, tournament_id, category_id, season, points_earned, position, team_result_id)
    VALUES (v_user_id, v_team_member_id, p_tournament_id, p_category_id, p_season, v_points, v_position, v_team_result_id)
    ON CONFLICT DO NOTHING
    RETURNING id INTO v_event_id;

    IF v_event_id IS NOT NULL THEN
      v_inserted := TRUE;
      v_inserted_count := v_inserted_count + 1;

      -- Upsert ranking table (add points)
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
