-- Migration: Tournament Brackets System
-- Date: 2026-01-25
-- Updated: 2026-02-15 (synced with actual DB schema)
-- Purpose: Deploy core bracket tables with indexes, RLS, and triggers
-- Dependencies: tournaments, categories, teams, users tables

-- ============================================================================
-- STEP 1: Create tournament_brackets table
-- ============================================================================

CREATE TABLE IF NOT EXISTS tournament_brackets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tournament_id uuid NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
  category_id integer NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  format text NOT NULL CHECK (format IN ('knockout', 'americano', 'mexicano', 'round_robin', 'groups_knockout')),
  status text DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'in_progress', 'completed', 'cancelled')),
  config jsonb DEFAULT '{}'::jsonb,
  seeding_method text DEFAULT 'random' CHECK (seeding_method IN ('random', 'manual', 'ranking')),
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now(),
  UNIQUE(tournament_id, category_id)
);

COMMENT ON TABLE tournament_brackets IS 'Bracket configuration per tournament category';
COMMENT ON COLUMN tournament_brackets.config IS 'Format-specific settings (JSONB): thirdPlaceMatch, setsToWin, etc.';

-- ============================================================================
-- STEP 2: Create tournament_matches table
-- ============================================================================

CREATE TABLE IF NOT EXISTS tournament_matches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  bracket_id uuid NOT NULL REFERENCES tournament_brackets(id) ON DELETE CASCADE,
  round_number integer NOT NULL,
  match_number integer NOT NULL,
  round_name text,
  court_number integer,
  scheduled_time timestamptz,

  team1_id uuid REFERENCES teams(id) ON DELETE RESTRICT,
  team1_player1_id text REFERENCES users(uid),
  team1_player2_id text REFERENCES users(uid),
  team1_seed integer,

  team2_id uuid REFERENCES teams(id) ON DELETE RESTRICT,
  team2_player1_id text REFERENCES users(uid),
  team2_player2_id text REFERENCES users(uid),
  team2_seed integer,

  score_team1 integer,
  score_team2 integer,
  set_scores jsonb,
  winner_team integer CHECK (winner_team IN (1, 2)),

  next_match_id uuid REFERENCES tournament_matches(id),
  next_match_position integer CHECK (next_match_position IN (1, 2)),
  loser_next_match_id uuid REFERENCES tournament_matches(id),
  loser_next_match_position integer CHECK (loser_next_match_position IN (1, 2)),

  group_number integer,

  status text DEFAULT 'pending' CHECK (status IN ('pending', 'scheduled', 'in_progress', 'completed', 'forfeit', 'bye')),
  is_bye boolean DEFAULT false,

  submitted_by_user_id text,
  submitted_at timestamptz,
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

COMMENT ON TABLE tournament_matches IS 'Individual matches within tournament brackets';
COMMENT ON COLUMN tournament_matches.set_scores IS 'Set-by-set scores (JSONB array): [{"team1": 6, "team2": 4}]';

-- ============================================================================
-- STEP 3: Create tournament_standings table
-- ============================================================================

CREATE TABLE IF NOT EXISTS tournament_standings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  bracket_id uuid NOT NULL REFERENCES tournament_brackets(id) ON DELETE CASCADE,
  player_id text REFERENCES users(uid),
  team_id uuid REFERENCES teams(id) ON DELETE CASCADE,
  group_number integer DEFAULT 0,
  total_points integer DEFAULT 0,
  matches_played integer DEFAULT 0,
  matches_won integer DEFAULT 0,
  matches_lost integer DEFAULT 0,
  games_won integer DEFAULT 0,
  games_lost integer DEFAULT 0,
  point_difference integer DEFAULT 0,
  position integer,
  round_reached text,
  head_to_head jsonb DEFAULT '{}'::jsonb,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now(),
  UNIQUE(bracket_id, player_id, group_number)
);

COMMENT ON TABLE tournament_standings IS 'Team/player standings for bracket formats (groups, knockout, round robin)';

-- ============================================================================
-- STEP 4: Create performance indexes
-- ============================================================================

-- Match indexes
CREATE INDEX idx_tournament_matches_bracket ON tournament_matches(bracket_id);
CREATE INDEX idx_tournament_matches_round ON tournament_matches(bracket_id, round_number);
CREATE INDEX idx_tournament_matches_scheduled ON tournament_matches(scheduled_time) WHERE scheduled_time IS NOT NULL;
CREATE INDEX idx_tournament_matches_status ON tournament_matches(status);
CREATE INDEX idx_tournament_matches_group ON tournament_matches(bracket_id, group_number) WHERE group_number IS NOT NULL;
CREATE INDEX idx_tournament_matches_next_match_id ON tournament_matches(next_match_id);
CREATE INDEX idx_tournament_matches_loser_next_match_id ON tournament_matches(loser_next_match_id);
CREATE INDEX idx_tournament_matches_team1_id ON tournament_matches(team1_id);
CREATE INDEX idx_tournament_matches_team2_id ON tournament_matches(team2_id);
CREATE INDEX idx_tournament_matches_team1_player1_id ON tournament_matches(team1_player1_id);
CREATE INDEX idx_tournament_matches_team1_player2_id ON tournament_matches(team1_player2_id);
CREATE INDEX idx_tournament_matches_team2_player1_id ON tournament_matches(team2_player1_id);
CREATE INDEX idx_tournament_matches_team2_player2_id ON tournament_matches(team2_player2_id);

-- Standings indexes
CREATE INDEX idx_tournament_standings_bracket ON tournament_standings(bracket_id);
CREATE INDEX idx_tournament_standings_position ON tournament_standings(bracket_id, position);
CREATE INDEX idx_tournament_standings_team_id ON tournament_standings(team_id);

-- Bracket indexes (foreign key performance)
CREATE INDEX idx_brackets_tournament ON tournament_brackets(tournament_id);
CREATE INDEX idx_brackets_category ON tournament_brackets(category_id);

-- Partial unique index for team-based standings (groups+knockout format)
CREATE UNIQUE INDEX idx_tournament_standings_team_group
  ON tournament_standings(bracket_id, team_id, group_number)
  WHERE team_id IS NOT NULL;

-- ============================================================================
-- STEP 5: Create updated_at triggers
-- ============================================================================

-- Reusable trigger function (CREATE OR REPLACE to handle existing)
CREATE OR REPLACE FUNCTION trigger_set_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply to each table
CREATE TRIGGER set_timestamp_tournament_brackets
BEFORE UPDATE ON tournament_brackets
FOR EACH ROW
EXECUTE FUNCTION trigger_set_timestamp();

CREATE TRIGGER set_timestamp_tournament_matches
BEFORE UPDATE ON tournament_matches
FOR EACH ROW
EXECUTE FUNCTION trigger_set_timestamp();

CREATE TRIGGER set_timestamp_tournament_standings
BEFORE UPDATE ON tournament_standings
FOR EACH ROW
EXECUTE FUNCTION trigger_set_timestamp();

-- ============================================================================
-- STEP 6: Create RLS policies
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE tournament_brackets ENABLE ROW LEVEL SECURITY;
ALTER TABLE tournament_matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE tournament_standings ENABLE ROW LEVEL SECURITY;

-- Brackets: Organizers can manage their tournament brackets
CREATE POLICY "Organizers can manage brackets"
ON tournament_brackets FOR ALL
USING (
  EXISTS (
    SELECT 1 FROM tournaments t
    WHERE t.id = tournament_brackets.tournament_id
    AND t.organizer_id = auth.uid()
  )
);

-- Brackets: Players can view published brackets
CREATE POLICY "Players can view published brackets"
ON tournament_brackets FOR SELECT
USING (status IN ('published', 'in_progress', 'completed'));

-- Matches: Follow bracket permissions
CREATE POLICY "Organizers can manage matches"
ON tournament_matches FOR ALL
USING (
  EXISTS (
    SELECT 1 FROM tournament_brackets tb
    JOIN tournaments t ON t.id = tb.tournament_id
    WHERE tb.id = tournament_matches.bracket_id
    AND t.organizer_id = auth.uid()
  )
);

CREATE POLICY "Players can view published matches"
ON tournament_matches FOR SELECT
USING (
  EXISTS (
    SELECT 1 FROM tournament_brackets tb
    WHERE tb.id = tournament_matches.bracket_id
    AND tb.status IN ('published', 'in_progress', 'completed')
  )
);

-- Standings: Follow bracket permissions
CREATE POLICY "Organizers can manage standings"
ON tournament_standings FOR ALL
USING (
  EXISTS (
    SELECT 1 FROM tournament_brackets tb
    JOIN tournaments t ON t.id = tb.tournament_id
    WHERE tb.id = tournament_standings.bracket_id
    AND t.organizer_id = auth.uid()
  )
);

CREATE POLICY "Players can view published standings"
ON tournament_standings FOR SELECT
USING (
  EXISTS (
    SELECT 1 FROM tournament_brackets tb
    WHERE tb.id = tournament_standings.bracket_id
    AND tb.status IN ('published', 'in_progress', 'completed')
  )
);

-- ============================================================================
-- STEP 7: Grant permissions
-- ============================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON tournament_brackets TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON tournament_matches TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON tournament_standings TO authenticated;

-- ============================================================================
-- STEP 8: Enable Realtime for these tables
-- ============================================================================

-- Enable realtime for tournament_brackets
ALTER PUBLICATION supabase_realtime ADD TABLE tournament_brackets;

-- Enable realtime for tournament_matches
ALTER PUBLICATION supabase_realtime ADD TABLE tournament_matches;

-- Enable realtime for tournament_standings
ALTER PUBLICATION supabase_realtime ADD TABLE tournament_standings;
