-- Add per-stat adjustment columns to league_adjustments table
-- These allow organizers to directly adjust individual player stats

ALTER TABLE league_adjustments
ADD COLUMN IF NOT EXISTS points_for_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS points_against_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS games_won_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS games_lost_adj INTEGER DEFAULT 0;

-- Add comment explaining the columns
COMMENT ON COLUMN league_adjustments.points_for_adj IS 'Adjustment to player points scored (positive or negative)';
COMMENT ON COLUMN league_adjustments.points_against_adj IS 'Adjustment to player points conceded (positive or negative)';
COMMENT ON COLUMN league_adjustments.games_won_adj IS 'Adjustment to games won count (positive or negative)';
COMMENT ON COLUMN league_adjustments.games_lost_adj IS 'Adjustment to games lost count (positive or negative)';

-- NOTE: The calculate_league_rankings function should be updated to include these adjustments.
-- The function should aggregate stat adjustments per player and add them to the base stats:
--   points_for = base_points_for + COALESCE(SUM(points_for_adj), 0)
--   points_against = base_points_against + COALESCE(SUM(points_against_adj), 0)
--   games_won = base_games_won + COALESCE(SUM(games_won_adj), 0)
--   games_lost = base_games_lost + COALESCE(SUM(games_lost_adj), 0)
