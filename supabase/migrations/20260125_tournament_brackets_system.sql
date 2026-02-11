-- Migration: Tournament Brackets System
-- Date: 2026-01-25
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
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'in_progress', 'completed', 'cancelled')),
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
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

  team1_id uuid REFERENCES teams(id),
  team1_player1_id text REFERENCES users(uid),
  team1_player2_id text REFERENCES users(uid),
  team1_seed integer,

  team2_id uuid REFERENCES teams(id),
  team2_player1_id text REFERENCES users(uid),
  team2_player2_id text REFERENCES users(uid),
  team2_seed integer,

  score_team1 integer,
  score_team2 integer,
  set_scores jsonb,
  winner_team integer CHECK (winner_team IN (1, 2)),

  next_match_id uuid REFERENCES tournament_matches(id),
  next_match_position integer CHECK (next_match_position IN (1, 2)),
  loser_next_match_id uuid,

  group_number integer,

  status text DEFAULT 'pending' CHECK (status IN ('pending', 'scheduled', 'in_progress', 'completed', 'bye', 'walkover', 'cancelled')),
  is_bye boolean DEFAULT false,

  submitted_by_user_id text REFERENCES users(uid),
  submitted_at timestamptz,
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
  team_id uuid REFERENCES teams(id),
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
  updated_at timestamptz DEFAULT now()
);

COMMENT ON TABLE tournament_standings IS 'Standings for tournament brackets (supports both player and team formats)';

-- ============================================================================
-- STEP 4: Create performance indexes
-- ============================================================================

-- Query optimization (most frequent queries)
CREATE INDEX idx_matches_bracket_round ON tournament_matches(bracket_id, round_number);
CREATE INDEX idx_matches_scheduled_time ON tournament_matches(scheduled_time) WHERE status = 'scheduled';
CREATE INDEX idx_matches_status ON tournament_matches(status);
CREATE INDEX idx_standings_bracket_position ON tournament_standings(bracket_id, position);

-- Real-time update tracking
CREATE INDEX idx_matches_updated ON tournament_matches(updated_at);
CREATE INDEX idx_standings_updated ON tournament_standings(updated_at);

-- Foreign key performance (prevent slow JOINs)
CREATE INDEX idx_brackets_tournament ON tournament_brackets(tournament_id);
CREATE INDEX idx_brackets_category ON tournament_brackets(category_id);

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
