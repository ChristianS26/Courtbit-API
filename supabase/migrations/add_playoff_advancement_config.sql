-- Add playoff advancement configuration to seasons table
ALTER TABLE seasons
ADD COLUMN IF NOT EXISTS players_direct_to_final INTEGER DEFAULT 2,
ADD COLUMN IF NOT EXISTS players_in_semifinals INTEGER DEFAULT 4;

-- Add constraints to ensure valid values
ALTER TABLE seasons
ADD CONSTRAINT check_players_direct_to_final CHECK (players_direct_to_final IN (0, 2, 4)),
ADD CONSTRAINT check_players_in_semifinals CHECK (players_in_semifinals IN (0, 4, 8));

-- Add comments explaining the fields
COMMENT ON COLUMN seasons.players_direct_to_final IS 'Number of players who advance directly to the final (0, 2, or 4)';
COMMENT ON COLUMN seasons.players_in_semifinals IS 'Number of players who play in semifinals (0, 4, or 8)';

-- Update existing seasons with default values
UPDATE seasons
SET players_direct_to_final = 2,
    players_in_semifinals = 4
WHERE players_direct_to_final IS NULL
   OR players_in_semifinals IS NULL;
