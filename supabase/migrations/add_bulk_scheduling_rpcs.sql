-- Bulk update match schedules in a single transaction
-- Takes a JSONB array of [{match_id, court_number, scheduled_time}, ...]
CREATE OR REPLACE FUNCTION bulk_update_match_schedules(p_updates jsonb)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  updated_count int := 0;
  rec jsonb;
BEGIN
  FOR rec IN SELECT * FROM jsonb_array_elements(p_updates)
  LOOP
    UPDATE tournament_matches
    SET
      court_number = (rec->>'court_number')::int,
      scheduled_time = (rec->>'scheduled_time')::timestamptz
    WHERE id = (rec->>'match_id')::uuid;

    IF FOUND THEN
      updated_count := updated_count + 1;
    END IF;
  END LOOP;

  RETURN updated_count;
END;
$$;

-- Clear all match schedules for a tournament in a single transaction
-- Sets court_number and scheduled_time to NULL for all scheduled matches
CREATE OR REPLACE FUNCTION clear_tournament_match_schedules(p_tournament_id uuid)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cleared_count int;
BEGIN
  UPDATE tournament_matches tm
  SET
    court_number = NULL,
    scheduled_time = NULL
  FROM tournament_brackets tb
  WHERE tm.bracket_id = tb.id
    AND tb.tournament_id = p_tournament_id
    AND (tm.court_number IS NOT NULL OR tm.scheduled_time IS NOT NULL);

  GET DIAGNOSTICS cleared_count = ROW_COUNT;
  RETURN cleared_count;
END;
$$;
