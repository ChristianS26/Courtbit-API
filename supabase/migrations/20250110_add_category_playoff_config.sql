-- Migration: Add playoff configuration at category level
-- This allows each category to override the season-level playoff settings
-- NULL values mean "use season default"

-- ============================================================================
-- STEP 1: Add columns to league_categories table
-- ============================================================================

ALTER TABLE league_categories
ADD COLUMN IF NOT EXISTS players_direct_to_final INTEGER DEFAULT NULL,
ADD COLUMN IF NOT EXISTS players_in_semifinals INTEGER DEFAULT NULL;

-- Add constraints (same as seasons table, but allow NULL for inheritance)
-- Drop existing constraints if they exist (for idempotent migration)
ALTER TABLE league_categories
DROP CONSTRAINT IF EXISTS check_category_players_direct_to_final,
DROP CONSTRAINT IF EXISTS check_category_players_in_semifinals;

ALTER TABLE league_categories
ADD CONSTRAINT check_category_players_direct_to_final
    CHECK (players_direct_to_final IS NULL OR players_direct_to_final IN (0, 2, 4)),
ADD CONSTRAINT check_category_players_in_semifinals
    CHECK (players_in_semifinals IS NULL OR players_in_semifinals IN (0, 4, 8));

-- Add comments
COMMENT ON COLUMN league_categories.players_direct_to_final IS
    'Number of players who advance directly to the final (0, 2, or 4). NULL = use season default.';
COMMENT ON COLUMN league_categories.players_in_semifinals IS
    'Number of players who play in semifinals (0, 4, or 8). NULL = use season default.';

-- ============================================================================
-- STEP 2: Helper function to get effective playoff config for a category
-- ============================================================================

CREATE OR REPLACE FUNCTION get_category_playoff_config(p_category_id uuid)
RETURNS TABLE (
    players_direct_to_final integer,
    players_in_semifinals integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_season_id uuid;
    v_cat_direct integer;
    v_cat_semis integer;
    v_season_direct integer;
    v_season_semis integer;
BEGIN
    -- Get category's own config and season_id
    SELECT
        lc.season_id,
        lc.players_direct_to_final,
        lc.players_in_semifinals
    INTO v_season_id, v_cat_direct, v_cat_semis
    FROM league_categories lc
    WHERE lc.id = p_category_id;

    -- Get season defaults
    SELECT
        s.players_direct_to_final,
        s.players_in_semifinals
    INTO v_season_direct, v_season_semis
    FROM seasons s
    WHERE s.id = v_season_id;

    -- Return effective values (category overrides season)
    RETURN QUERY SELECT
        COALESCE(v_cat_direct, v_season_direct, 2) as players_direct_to_final,
        COALESCE(v_cat_semis, v_season_semis, 4) as players_in_semifinals;
END;
$function$;

COMMENT ON FUNCTION get_category_playoff_config(uuid) IS
    'Returns effective playoff configuration for a category. Uses category-level override if set, otherwise falls back to season default.';

-- ============================================================================
-- STEP 3: Update assign_semifinals_players to use category config
-- ============================================================================

CREATE OR REPLACE FUNCTION assign_semifinals_players(p_category_id uuid)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_season_id uuid;
    v_players_direct_to_final integer;
    v_players_in_semifinals integer;
    v_ranked_players uuid[];
    v_semis_matchday_id uuid;
    v_group_id uuid;
    v_group_number integer;
    v_players_for_group uuid[];
BEGIN
    -- Validate regular season is complete
    IF NOT is_regular_season_complete(p_category_id) THEN
        RAISE EXCEPTION 'Cannot assign semifinals players: regular season is not complete';
    END IF;

    -- Get season_id for the category
    SELECT season_id INTO v_season_id
    FROM league_categories
    WHERE id = p_category_id;

    -- Get effective playoff config (category override or season default)
    SELECT
        pc.players_direct_to_final,
        pc.players_in_semifinals
    INTO v_players_direct_to_final, v_players_in_semifinals
    FROM get_category_playoff_config(p_category_id) pc;

    IF v_players_in_semifinals = 0 THEN
        RETURN json_build_object('success', true, 'message', 'No semifinals configured');
    END IF;

    -- Get ranked players with tiebreaker (points against)
    SELECT array_agg(player_id ORDER BY adjusted_points_for DESC, points_against ASC)
    INTO v_ranked_players
    FROM (
        SELECT
            lp.id as player_id,
            COALESCE(SUM(
                CASE
                    WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                        CASE
                            WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team1
                            WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team2
                            ELSE 0
                        END
                    ELSE 0
                END
            ), 0) as adjusted_points_for,
            COALESCE(SUM(
                CASE
                    WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                        CASE
                            WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team2
                            WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team1
                            ELSE 0
                        END
                    ELSE 0
                END
            ), 0) as points_against
        FROM league_players lp
        LEFT JOIN doubles_matches dm ON (
            dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id OR
            dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id
        )
        LEFT JOIN rotations r ON r.id = dm.rotation_id
        LEFT JOIN day_groups dg ON dg.id = r.day_group_id
        LEFT JOIN match_days md ON md.id = dg.match_day_id
        WHERE lp.category_id = p_category_id
          AND lp.is_waiting_list = false
          AND md.match_number <= 5
        GROUP BY lp.id
    ) rankings;

    SELECT id INTO v_semis_matchday_id
    FROM match_days
    WHERE category_id = p_category_id
      AND match_number = 6;

    IF v_semis_matchday_id IS NULL THEN
        RAISE EXCEPTION 'Semifinals matchday not found';
    END IF;

    IF v_players_in_semifinals = 4 THEN
        v_players_for_group := ARRAY[
            v_ranked_players[v_players_direct_to_final + 1],
            v_ranked_players[v_players_direct_to_final + 2],
            v_ranked_players[v_players_direct_to_final + 3],
            v_ranked_players[v_players_direct_to_final + 4]
        ];

        UPDATE day_groups
        SET player_ids = v_players_for_group
        WHERE match_day_id = v_semis_matchday_id
          AND group_number = 1;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_semis_matchday_id AND group_number = 1));

    ELSIF v_players_in_semifinals = 8 THEN
        v_players_for_group := ARRAY[
            v_ranked_players[v_players_direct_to_final + 1],
            v_ranked_players[v_players_direct_to_final + 4],
            v_ranked_players[v_players_direct_to_final + 5],
            v_ranked_players[v_players_direct_to_final + 8]
        ];

        UPDATE day_groups
        SET player_ids = v_players_for_group
        WHERE match_day_id = v_semis_matchday_id
          AND group_number = 1;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_semis_matchday_id AND group_number = 1));

        v_players_for_group := ARRAY[
            v_ranked_players[v_players_direct_to_final + 2],
            v_ranked_players[v_players_direct_to_final + 3],
            v_ranked_players[v_players_direct_to_final + 6],
            v_ranked_players[v_players_direct_to_final + 7]
        ];

        UPDATE day_groups
        SET player_ids = v_players_for_group
        WHERE match_day_id = v_semis_matchday_id
          AND group_number = 2;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_semis_matchday_id AND group_number = 2));
    END IF;

    RETURN json_build_object(
        'success', true,
        'playersAssigned', v_players_in_semifinals,
        'groups', v_players_in_semifinals / 4,
        'configSource', CASE
            WHEN (SELECT lc.players_in_semifinals FROM league_categories lc WHERE lc.id = p_category_id) IS NOT NULL
            THEN 'category'
            ELSE 'season'
        END
    );
END;
$function$;

-- ============================================================================
-- STEP 4: Update assign_final_players to use category config
-- ============================================================================

CREATE OR REPLACE FUNCTION assign_final_players(p_category_id uuid)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_season_id uuid;
    v_players_direct_to_final integer;
    v_players_in_semifinals integer;
    v_ranked_players uuid[];
    v_semis_winners uuid[];
    v_final_matchday_id uuid;
    v_final_players uuid[];
    v_num_final_groups integer;
    v_players_for_group uuid[];
    v_group_record record;
BEGIN
    -- Get season_id
    SELECT season_id INTO v_season_id
    FROM league_categories
    WHERE id = p_category_id;

    -- Get effective playoff config (category override or season default)
    SELECT
        pc.players_direct_to_final,
        pc.players_in_semifinals
    INTO v_players_direct_to_final, v_players_in_semifinals
    FROM get_category_playoff_config(p_category_id) pc;

    -- Get top N players who qualified directly (from regular season rankings)
    SELECT array_agg(player_id ORDER BY adjusted_points_for DESC, points_against ASC)
    INTO v_ranked_players
    FROM (
        SELECT
            lp.id as player_id,
            COALESCE(SUM(
                CASE
                    WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                        CASE
                            WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team1
                            WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team2
                            ELSE 0
                        END
                    ELSE 0
                END
            ), 0) as adjusted_points_for,
            COALESCE(SUM(
                CASE
                    WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                        CASE
                            WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team2
                            WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team1
                            ELSE 0
                        END
                    ELSE 0
                END
            ), 0) as points_against
        FROM league_players lp
        LEFT JOIN doubles_matches dm ON (
            dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id OR
            dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id
        )
        LEFT JOIN rotations r ON r.id = dm.rotation_id
        LEFT JOIN day_groups dg ON dg.id = r.day_group_id
        LEFT JOIN match_days md ON md.id = dg.match_day_id
        WHERE lp.category_id = p_category_id
          AND lp.is_waiting_list = false
          AND md.match_number <= 5  -- Only regular season
        GROUP BY lp.id
        ORDER BY adjusted_points_for DESC, points_against ASC
        LIMIT v_players_direct_to_final
    ) direct_qualifiers;

    -- Get semifinals winners (top 2 from each semifinals group by points)
    IF v_players_in_semifinals > 0 THEN
        v_semis_winners := ARRAY[]::uuid[];

        -- Loop through each semifinals group
        FOR v_group_record IN
            SELECT dg.id as group_id, dg.group_number
            FROM day_groups dg
            JOIN match_days md ON md.id = dg.match_day_id
            WHERE md.category_id = p_category_id
              AND md.match_number = 6
            ORDER BY dg.group_number
        LOOP
            -- Get top 2 players from this group
            SELECT array_agg(player_id ORDER BY points_for DESC, points_against ASC)
            INTO v_players_for_group
            FROM (
                SELECT
                    lp.id as player_id,
                    COALESCE(SUM(
                        CASE
                            WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                                CASE
                                    WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team1
                                    WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team2
                                    ELSE 0
                                END
                            ELSE 0
                        END
                    ), 0) as points_for,
                    COALESCE(SUM(
                        CASE
                            WHEN dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL THEN
                                CASE
                                    WHEN (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id) THEN dm.score_team2
                                    WHEN (dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id) THEN dm.score_team1
                                    ELSE 0
                                END
                            ELSE 0
                        END
                    ), 0) as points_against
                FROM league_players lp
                JOIN doubles_matches dm ON (
                    dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id OR
                    dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id
                )
                JOIN rotations r ON r.id = dm.rotation_id
                WHERE r.day_group_id = v_group_record.group_id
                  AND lp.category_id = p_category_id
                GROUP BY lp.id
                ORDER BY points_for DESC, points_against ASC
                LIMIT 2
            ) group_winners;

            -- Add winners from this group to semis_winners array
            v_semis_winners := v_semis_winners || v_players_for_group;
        END LOOP;
    ELSE
        v_semis_winners := ARRAY[]::uuid[];
    END IF;

    -- Combine direct qualifiers + semifinals winners
    v_final_players := v_ranked_players || COALESCE(v_semis_winners, ARRAY[]::uuid[]);

    -- Get final matchday
    SELECT id INTO v_final_matchday_id
    FROM match_days
    WHERE category_id = p_category_id
      AND match_number = 7;

    IF v_final_matchday_id IS NULL THEN
        RAISE EXCEPTION 'Final matchday not found';
    END IF;

    -- Determine number of final groups
    v_num_final_groups := CASE
        WHEN array_length(v_final_players, 1) <= 4 THEN 1
        ELSE 2
    END;

    IF v_num_final_groups = 1 THEN
        -- 1 group: assign all players
        UPDATE day_groups
        SET player_ids = v_final_players[1:4]
        WHERE match_day_id = v_final_matchday_id
          AND group_number = 1;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_final_matchday_id AND group_number = 1));
    ELSE
        -- 2 groups: split players with seeding
        -- Group 1: positions 1, 4, 5, 8
        v_players_for_group := ARRAY[
            v_final_players[1],
            v_final_players[4],
            v_final_players[5],
            v_final_players[8]
        ];

        UPDATE day_groups
        SET player_ids = v_players_for_group
        WHERE match_day_id = v_final_matchday_id
          AND group_number = 1;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_final_matchday_id AND group_number = 1));

        -- Group 2: positions 2, 3, 6, 7
        v_players_for_group := ARRAY[
            v_final_players[2],
            v_final_players[3],
            v_final_players[6],
            v_final_players[7]
        ];

        UPDATE day_groups
        SET player_ids = v_players_for_group
        WHERE match_day_id = v_final_matchday_id
          AND group_number = 2;

        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_final_matchday_id AND group_number = 2));
    END IF;

    RETURN json_build_object(
        'success', true,
        'directQualifiers', v_players_direct_to_final,
        'semifinalsWinners', array_length(v_semis_winners, 1),
        'totalInFinal', array_length(v_final_players, 1),
        'finalGroups', v_num_final_groups,
        'configSource', CASE
            WHEN (SELECT lc.players_direct_to_final FROM league_categories lc WHERE lc.id = p_category_id) IS NOT NULL
            THEN 'category'
            ELSE 'season'
        END
    );
END;
$function$;

-- ============================================================================
-- STEP 5: Create view for easy access to category playoff config
-- ============================================================================

CREATE OR REPLACE VIEW category_playoff_config AS
SELECT
    lc.id as category_id,
    lc.name as category_name,
    lc.season_id,
    s.name as season_name,
    lc.players_direct_to_final as category_direct_to_final,
    lc.players_in_semifinals as category_in_semifinals,
    s.players_direct_to_final as season_direct_to_final,
    s.players_in_semifinals as season_in_semifinals,
    COALESCE(lc.players_direct_to_final, s.players_direct_to_final, 2) as effective_direct_to_final,
    COALESCE(lc.players_in_semifinals, s.players_in_semifinals, 4) as effective_in_semifinals,
    CASE
        WHEN lc.players_direct_to_final IS NOT NULL OR lc.players_in_semifinals IS NOT NULL
        THEN 'category'
        ELSE 'season'
    END as config_source
FROM league_categories lc
JOIN seasons s ON s.id = lc.season_id;

COMMENT ON VIEW category_playoff_config IS
    'View showing effective playoff configuration for each category, including source (category override vs season default)';

-- ============================================================================
-- STEP 6: Grant permissions
-- ============================================================================

GRANT SELECT ON category_playoff_config TO authenticated;
GRANT EXECUTE ON FUNCTION get_category_playoff_config(uuid) TO authenticated;
