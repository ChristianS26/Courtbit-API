-- Add registrations_open to seasons table
ALTER TABLE seasons
ADD COLUMN IF NOT EXISTS registrations_open BOOLEAN DEFAULT false;

-- Add is_waiting_list to league_players table
ALTER TABLE league_players
ADD COLUMN IF NOT EXISTS is_waiting_list BOOLEAN DEFAULT false;

-- Create or replace function to get detailed category info with player counts
CREATE OR REPLACE FUNCTION get_league_category_with_counts(category_id_param UUID)
RETURNS TABLE (
    id UUID,
    season_id UUID,
    name TEXT,
    level TEXT,
    color_hex TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    player_count BIGINT,
    waiting_list_count BIGINT,
    has_calendar BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        lc.id,
        lc.season_id,
        lc.name,
        lc.level,
        lc.color_hex,
        lc.created_at,
        lc.updated_at,
        COUNT(lp.id) FILTER (WHERE lp.is_waiting_list = false) AS player_count,
        COUNT(lp.id) FILTER (WHERE lp.is_waiting_list = true) AS waiting_list_count,
        EXISTS(
            SELECT 1 FROM match_days md
            WHERE md.category_id = lc.id
        ) AS has_calendar
    FROM league_categories lc
    LEFT JOIN league_players lp ON lp.category_id = lc.id
    WHERE lc.id = category_id_param
    GROUP BY lc.id, lc.season_id, lc.name, lc.level, lc.color_hex, lc.created_at, lc.updated_at;
END;
$$ LANGUAGE plpgsql;

-- Create index for better performance
CREATE INDEX IF NOT EXISTS idx_league_players_category_waiting
ON league_players(category_id, is_waiting_list);

-- Update existing league_players to have is_waiting_list = false
UPDATE league_players
SET is_waiting_list = false
WHERE is_waiting_list IS NULL;

-- Update existing seasons to have registrations_open = false
UPDATE seasons
SET registrations_open = false
WHERE registrations_open IS NULL;

-- Add comment explaining the waiting list logic
COMMENT ON COLUMN league_players.is_waiting_list IS 'Players beyond the 16-player limit are automatically placed on waiting list';
COMMENT ON COLUMN seasons.registrations_open IS 'Controls whether new player registrations are accepted for this season';
