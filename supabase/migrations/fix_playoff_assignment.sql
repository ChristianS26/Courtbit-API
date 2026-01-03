-- Fixed version of assign_final_players with correct semifinals winners calculation
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
    -- Get season info
    SELECT season_id INTO v_season_id
    FROM league_categories
    WHERE id = p_category_id;

    SELECT players_direct_to_final, players_in_semifinals
    INTO v_players_direct_to_final, v_players_in_semifinals
    FROM seasons
    WHERE id = v_season_id;

    v_players_direct_to_final := COALESCE(v_players_direct_to_final, 2);
    v_players_in_semifinals := COALESCE(v_players_in_semifinals, 4);

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
        'finalGroups', v_num_final_groups
    );
END;
$function$;

-- Add validation function to check if regular season is complete
CREATE OR REPLACE FUNCTION is_regular_season_complete(p_category_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_total_matches integer;
    v_scored_matches integer;
BEGIN
    -- Count total regular season matches
    SELECT COUNT(dm.id)
    INTO v_total_matches
    FROM doubles_matches dm
    JOIN rotations r ON r.id = dm.rotation_id
    JOIN day_groups dg ON dg.id = r.day_group_id
    JOIN match_days md ON md.id = dg.match_day_id
    WHERE md.category_id = p_category_id
      AND md.match_number <= 5;

    -- Count scored matches
    SELECT COUNT(dm.id)
    INTO v_scored_matches
    FROM doubles_matches dm
    JOIN rotations r ON r.id = dm.rotation_id
    JOIN day_groups dg ON dg.id = r.day_group_id
    JOIN match_days md ON md.id = dg.match_day_id
    WHERE md.category_id = p_category_id
      AND md.match_number <= 5
      AND dm.score_team1 IS NOT NULL
      AND dm.score_team2 IS NOT NULL;

    RETURN v_total_matches > 0 AND v_total_matches = v_scored_matches;
END;
$function$;

-- Add validation function to check if semifinals are complete
CREATE OR REPLACE FUNCTION are_semifinals_complete(p_category_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_total_matches integer;
    v_scored_matches integer;
BEGIN
    -- Count total semifinals matches
    SELECT COUNT(dm.id)
    INTO v_total_matches
    FROM doubles_matches dm
    JOIN rotations r ON r.id = dm.rotation_id
    JOIN day_groups dg ON dg.id = r.day_group_id
    JOIN match_days md ON md.id = dg.match_day_id
    WHERE md.category_id = p_category_id
      AND md.match_number = 6;

    -- If no semifinals matchday exists, consider it complete
    IF v_total_matches = 0 THEN
        RETURN true;
    END IF;

    -- Count scored matches
    SELECT COUNT(dm.id)
    INTO v_scored_matches
    FROM doubles_matches dm
    JOIN rotations r ON r.id = dm.rotation_id
    JOIN day_groups dg ON dg.id = r.day_group_id
    JOIN match_days md ON md.id = dg.match_day_id
    WHERE md.category_id = p_category_id
      AND md.match_number = 6
      AND dm.score_team1 IS NOT NULL
      AND dm.score_team2 IS NOT NULL;

    RETURN v_total_matches = v_scored_matches;
END;
$function$;

-- Update assign_semifinals_players to include validation
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

    -- Get season info
    SELECT season_id INTO v_season_id
    FROM league_categories
    WHERE id = p_category_id;

    SELECT players_direct_to_final, players_in_semifinals
    INTO v_players_direct_to_final, v_players_in_semifinals
    FROM seasons
    WHERE id = v_season_id;

    v_players_direct_to_final := COALESCE(v_players_direct_to_final, 2);
    v_players_in_semifinals := COALESCE(v_players_in_semifinals, 4);

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
        'groups', v_players_in_semifinals / 4
    );
END;
$function$;

-- Update assign_final_players to add validation
CREATE OR REPLACE FUNCTION assign_final_players_validated(p_category_id uuid)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    -- Validate prerequisites
    IF NOT is_regular_season_complete(p_category_id) THEN
        RAISE EXCEPTION 'Cannot assign final players: regular season is not complete';
    END IF;

    IF NOT are_semifinals_complete(p_category_id) THEN
        RAISE EXCEPTION 'Cannot assign final players: semifinals are not complete';
    END IF;

    -- Call the main assignment function
    RETURN assign_final_players(p_category_id);
END;
$function$;

COMMENT ON FUNCTION assign_final_players(uuid) IS 'Assigns players to final groups. Includes direct qualifiers + semifinals winners. Internal function - use assign_final_players_validated for proper validation.';
COMMENT ON FUNCTION assign_final_players_validated(uuid) IS 'Public function to assign final players with validation. Checks that regular season and semifinals are complete before assignment.';
COMMENT ON FUNCTION is_regular_season_complete(uuid) IS 'Returns true if all regular season matches (matchdays 1-5) have scores entered.';
COMMENT ON FUNCTION are_semifinals_complete(uuid) IS 'Returns true if all semifinals matches have scores entered, or if no semifinals exist.';
