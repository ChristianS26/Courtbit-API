-- Add matchday_dates to seasons table
-- This stores the 7 matchday dates (5 regular + semifinals + final) selected during season creation
ALTER TABLE seasons
ADD COLUMN IF NOT EXISTS matchday_dates TEXT[] DEFAULT NULL;

-- Add comment explaining the field
COMMENT ON COLUMN seasons.matchday_dates IS 'Array of 7 dates for matchdays: 5 regular matchdays, semifinals, and final. Format: yyyy-MM-dd';
