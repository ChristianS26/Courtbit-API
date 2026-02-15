-- ============================================================
-- Migration: ranking_seasons
-- Create ranking_seasons table for per-organizer season management
-- Migrate existing ranking data to reference the new season UUIDs
-- ============================================================

-- 1. Create ranking_seasons table
CREATE TABLE IF NOT EXISTS ranking_seasons (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organizer_id UUID NOT NULL REFERENCES organizers(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'archived')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. Unique partial index: only one active season per organizer
CREATE UNIQUE INDEX idx_ranking_seasons_one_active_per_org
    ON ranking_seasons (organizer_id)
    WHERE status = 'active';

-- 3. Regular indexes
CREATE INDEX idx_ranking_seasons_organizer ON ranking_seasons (organizer_id);
CREATE INDEX idx_ranking_seasons_status ON ranking_seasons (status);

-- 4. Enable RLS
ALTER TABLE ranking_seasons ENABLE ROW LEVEL SECURITY;

-- Service role full access
CREATE POLICY "Service role full access on ranking_seasons"
    ON ranking_seasons
    FOR ALL
    TO service_role
    USING (true)
    WITH CHECK (true);

-- Authenticated users can read
CREATE POLICY "Authenticated users can read ranking_seasons"
    ON ranking_seasons
    FOR SELECT
    TO authenticated
    USING (true);

-- 5. Data migration: create a default season for each organizer that has existing ranking data
INSERT INTO ranking_seasons (organizer_id, name, start_date, end_date, status)
SELECT DISTINCT
    r.organizer_id,
    'Temporada ' || r.season,
    (r.season || '-01-01')::DATE,
    (r.season || '-12-31')::DATE,
    'active'
FROM ranking r
WHERE r.organizer_id IS NOT NULL
  AND r.season IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ranking_seasons rs
      WHERE rs.organizer_id = r.organizer_id
  )
ON CONFLICT DO NOTHING;

-- 6. Update ranking.season to point to the new ranking_season UUID
UPDATE ranking r
SET season = rs.id::TEXT
FROM ranking_seasons rs
WHERE r.organizer_id = rs.organizer_id
  AND r.season NOT LIKE '%-%-%-%'; -- only update rows that haven't been migrated (not UUID format)

-- 7. Update ranking_events.season to point to the new ranking_season UUID
UPDATE ranking_events re
SET season = rs.id::TEXT
FROM ranking_seasons rs
WHERE re.organization_id = rs.organizer_id
  AND re.season NOT LIKE '%-%-%-%'; -- only update rows that haven't been migrated
