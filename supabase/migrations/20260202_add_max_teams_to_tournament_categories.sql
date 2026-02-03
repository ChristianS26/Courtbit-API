-- Migration: Add max_teams column to tournament_categories
-- This allows organizers to set a maximum number of teams per category in a tournament

ALTER TABLE tournament_categories
ADD COLUMN IF NOT EXISTS max_teams INT DEFAULT NULL;

-- Add check constraint to ensure max_teams is positive when set
ALTER TABLE tournament_categories
ADD CONSTRAINT chk_max_teams_positive CHECK (max_teams IS NULL OR max_teams > 0);

-- Add comment for documentation
COMMENT ON COLUMN tournament_categories.max_teams IS 'Maximum number of teams allowed for this category in this tournament. NULL means unlimited.';
