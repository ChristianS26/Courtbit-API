-- Function to assign players to semifinals based on rankings
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
    -- Get season info
    SELECT season_id INTO v_season_id
    FROM league_categories
    WHERE id = p_category_id;

    SELECT players_direct_to_final, players_in_semifinals
    INTO v_players_direct_to_final, v_players_in_semifinals
    FROM seasons
    WHERE id = v_season_id;

    -- Default values
    v_players_direct_to_final := COALESCE(v_players_direct_to_final, 2);
    v_players_in_semifinals := COALESCE(v_players_in_semifinals, 4);

    -- If no semifinals, nothing to assign
    IF v_players_in_semifinals = 0 THEN
        RETURN json_build_object('success', true, 'message', 'No semifinals configured');
    END IF;

    -- Get ranked players (ordered by adjusted points descending)
    SELECT array_agg(player_id ORDER BY adjusted_points_for DESC)
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
            ), 0) as adjusted_points_for
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
    ) rankings;

    -- Get semifinals matchday
    SELECT id INTO v_semis_matchday_id
    FROM match_days
    WHERE category_id = p_category_id
      AND match_number = 6;

    IF v_semis_matchday_id IS NULL THEN
        RAISE EXCEPTION 'Semifinals matchday not found';
    END IF;

    -- Assign players based on configuration
    IF v_players_in_semifinals = 4 THEN
        -- 1 group: players ranked 3, 4, 5, 6 (after top 2 who go direct)
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

        -- Assign matches for this group
        PERFORM assign_group_matches((SELECT id FROM day_groups WHERE match_day_id = v_semis_matchday_id AND group_number = 1));

    ELSIF v_players_in_semifinals = 8 THEN
        -- 2 groups with competitive seeding
        -- Group 1: players ranked 3, 6, 7, 10 (positions after direct qualifiers)
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

        -- Group 2: players ranked 4, 5, 8, 9
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

-- Function to assign matches within a group based on player_ids
CREATE OR REPLACE FUNCTION assign_group_matches(p_group_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_players uuid[];
    v_rotation record;
BEGIN
    -- Get players for this group
    SELECT player_ids INTO v_players
    FROM day_groups
    WHERE id = p_group_id;

    -- Assign players to matches in each rotation
    FOR v_rotation IN
        SELECT r.id, r.rotation_number
        FROM rotations r
        WHERE r.day_group_id = p_group_id
        ORDER BY r.rotation_number
    LOOP
        IF v_rotation.rotation_number = 1 THEN
            -- Rotation 1: Players 1,2 vs 3,4
            UPDATE doubles_matches
            SET team1_player1_id = v_players[1],
                team1_player2_id = v_players[2],
                team2_player1_id = v_players[3],
                team2_player2_id = v_players[4]
            WHERE rotation_id = v_rotation.id;
        ELSIF v_rotation.rotation_number = 2 THEN
            -- Rotation 2: Players 1,3 vs 2,4
            UPDATE doubles_matches
            SET team1_player1_id = v_players[1],
                team1_player2_id = v_players[3],
                team2_player1_id = v_players[2],
                team2_player2_id = v_players[4]
            WHERE rotation_id = v_rotation.id;
        ELSE
            -- Rotation 3: Players 1,4 vs 2,3
            UPDATE doubles_matches
            SET team1_player1_id = v_players[1],
                team1_player2_id = v_players[4],
                team2_player1_id = v_players[2],
                team2_player2_id = v_players[3]
            WHERE rotation_id = v_rotation.id;
        END IF;
    END LOOP;
END;
$function$;

-- Function to assign players to final based on rankings + semifinals results
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
    SELECT array_agg(player_id ORDER BY adjusted_points_for DESC)
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
            ), 0) as adjusted_points_for
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
        LIMIT v_players_direct_to_final
    ) direct_qualifiers;

    -- Get semifinals winners (top 2 from each semifinals group by points)
    IF v_players_in_semifinals > 0 THEN
        SELECT array_agg(player_id)
        INTO v_semis_winners
        FROM (
            SELECT DISTINCT ON (dg.group_number)
                unnest(player_ids_ranked) as player_id
            FROM (
                SELECT
                    dg.id,
                    dg.group_number,
                    array_agg(lp.id ORDER BY
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
                        ), 0) DESC
                    ) FILTER (WHERE lp.id = ANY(dg.player_ids)) as player_ids_ranked
                FROM day_groups dg
                JOIN match_days md ON md.id = dg.match_day_id
                CROSS JOIN league_players lp
                LEFT JOIN doubles_matches dm ON (
                    (dm.team1_player1_id = lp.id OR dm.team1_player2_id = lp.id OR
                     dm.team2_player1_id = lp.id OR dm.team2_player2_id = lp.id)
                )
                LEFT JOIN rotations r ON r.id = dm.rotation_id AND r.day_group_id = dg.id
                WHERE md.category_id = p_category_id
                  AND md.match_number = 6  -- Semifinals
                  AND lp.id = ANY(dg.player_ids)
                GROUP BY dg.id, dg.group_number
            ) semis_rankings
            ORDER BY group_number, player_ids_ranked
        ) top_2_per_group;
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

-- Add comments
COMMENT ON FUNCTION assign_semifinals_players(uuid) IS 'Assigns players to semifinals groups based on regular season rankings. Excludes top N players (players_direct_to_final) who advance directly to final. Uses competitive seeding for balanced groups.';
COMMENT ON FUNCTION assign_group_matches(uuid) IS 'Helper function to assign player matchups within a group based on the player_ids array. Creates 3 rotation matchups.';
COMMENT ON FUNCTION assign_final_players(uuid) IS 'Assigns players to final groups. Includes direct qualifiers from regular season + top 2 from each semifinals group. Uses competitive seeding for balanced groups.';
