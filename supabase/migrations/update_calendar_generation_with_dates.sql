-- Update generate_league_calendar to use season matchday_dates and create all 7 matchdays
CREATE OR REPLACE FUNCTION public.generate_league_calendar(p_category_id uuid)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_player_count integer;
    v_player_ids uuid[];
    v_season_id uuid;
    v_matchday_dates text[];
    v_matchday_id uuid;
    v_group_id uuid;
    v_rotation_id uuid;
    v_round_config integer[][];
    v_round integer;
    v_group integer;
    v_rotation integer;
    v_group_players uuid[];
    v_current_date text;
BEGIN
    -- Validate exactly 16 players and get season info
    SELECT COUNT(*), array_agg(league_players.id ORDER BY league_players.name), category.season_id
    INTO v_player_count, v_player_ids, v_season_id
    FROM league_players
    JOIN league_categories category ON category.id = league_players.category_id
    WHERE league_players.category_id = p_category_id
      AND league_players.is_waiting_list = false
    GROUP BY category.season_id;

    IF v_player_count != 16 THEN
        RAISE EXCEPTION 'Category must have exactly 16 active players (has %)', v_player_count;
    END IF;

    -- Get matchday dates from season
    SELECT matchday_dates INTO v_matchday_dates
    FROM seasons
    WHERE id = v_season_id;

    -- Delete existing calendar if any
    DELETE FROM match_days WHERE category_id = p_category_id;

    -- Fixed pairing algorithm (5 regular rounds)
    v_round_config := ARRAY[
        ARRAY[0,1,2,3, 4,5,6,7, 8,9,10,11, 12,13,14,15],  -- Round 1
        ARRAY[0,4,8,12, 1,5,9,13, 2,6,10,14, 3,7,11,15],  -- Round 2
        ARRAY[0,5,10,15, 1,4,11,14, 2,7,8,13, 3,6,9,12],  -- Round 3
        ARRAY[0,13,6,11, 1,7,10,12, 2,4,9,15, 3,5,8,14],  -- Round 4
        ARRAY[0,7,9,14, 1,6,8,15, 2,5,11,12, 3,4,10,13]   -- Round 5
    ];

    -- Generate 5 regular matchdays
    FOR v_round IN 1..5 LOOP
        INSERT INTO match_days (category_id, match_number)
        VALUES (p_category_id, v_round)
        RETURNING id INTO v_matchday_id;

        -- Get date for this matchday if available
        IF v_matchday_dates IS NOT NULL AND array_length(v_matchday_dates, 1) >= v_round THEN
            v_current_date := v_matchday_dates[v_round];
        ELSE
            v_current_date := NULL;
        END IF;

        -- Generate 4 groups per matchday
        FOR v_group IN 1..4 LOOP
            -- Extract 4 players for this group
            v_group_players := ARRAY[
                v_player_ids[v_round_config[v_round][(v_group-1)*4 + 1] + 1],
                v_player_ids[v_round_config[v_round][(v_group-1)*4 + 2] + 1],
                v_player_ids[v_round_config[v_round][(v_group-1)*4 + 3] + 1],
                v_player_ids[v_round_config[v_round][(v_group-1)*4 + 4] + 1]
            ];

            INSERT INTO day_groups (match_day_id, group_number, player_ids, match_date)
            VALUES (v_matchday_id, v_group, v_group_players, v_current_date::date)
            RETURNING id INTO v_group_id;

            -- Generate 3 rotations per group
            FOR v_rotation IN 1..3 LOOP
                INSERT INTO rotations (day_group_id, rotation_number)
                VALUES (v_group_id, v_rotation)
                RETURNING id INTO v_rotation_id;

                -- Create doubles match with player assignments
                IF v_rotation = 1 THEN
                    INSERT INTO doubles_matches (rotation_id, team1_player1_id, team1_player2_id, team2_player1_id, team2_player2_id)
                    VALUES (v_rotation_id, v_group_players[1], v_group_players[2], v_group_players[3], v_group_players[4]);
                ELSIF v_rotation = 2 THEN
                    INSERT INTO doubles_matches (rotation_id, team1_player1_id, team1_player2_id, team2_player1_id, team2_player2_id)
                    VALUES (v_rotation_id, v_group_players[1], v_group_players[3], v_group_players[2], v_group_players[4]);
                ELSE
                    INSERT INTO doubles_matches (rotation_id, team1_player1_id, team1_player2_id, team2_player1_id, team2_player2_id)
                    VALUES (v_rotation_id, v_group_players[1], v_group_players[4], v_group_players[2], v_group_players[3]);
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    -- Matchday 6: Semifinals (2 groups of 4 - top 8 players by ranking)
    -- Note: Groups are placeholders until rankings determine the top 8
    INSERT INTO match_days (category_id, match_number)
    VALUES (p_category_id, 6)
    RETURNING id INTO v_matchday_id;

    -- Get date for semifinals if available
    IF v_matchday_dates IS NOT NULL AND array_length(v_matchday_dates, 1) >= 6 THEN
        v_current_date := v_matchday_dates[6];
    ELSE
        v_current_date := NULL;
    END IF;

    -- Create 2 placeholder groups for semifinals (players TBD based on rankings)
    FOR v_group IN 1..2 LOOP
        -- Empty player_ids - will be filled later based on rankings
        INSERT INTO day_groups (match_day_id, group_number, player_ids, match_date)
        VALUES (v_matchday_id, v_group, ARRAY[]::uuid[], v_current_date::date)
        RETURNING id INTO v_group_id;

        -- Create 3 rotations per group (matches will be assigned when players are determined)
        FOR v_rotation IN 1..3 LOOP
            INSERT INTO rotations (day_group_id, rotation_number)
            VALUES (v_group_id, v_rotation)
            RETURNING id INTO v_rotation_id;

            -- Create empty doubles match
            INSERT INTO doubles_matches (rotation_id, team1_player1_id, team1_player2_id, team2_player1_id, team2_player2_id)
            VALUES (v_rotation_id, NULL, NULL, NULL, NULL);
        END LOOP;
    END LOOP;

    -- Matchday 7: Final (1 group of 4 - top 4 players by ranking)
    -- Note: Group is a placeholder until rankings determine the top 4
    INSERT INTO match_days (category_id, match_number)
    VALUES (p_category_id, 7)
    RETURNING id INTO v_matchday_id;

    -- Get date for final if available
    IF v_matchday_dates IS NOT NULL AND array_length(v_matchday_dates, 1) >= 7 THEN
        v_current_date := v_matchday_dates[7];
    ELSE
        v_current_date := NULL;
    END IF;

    -- Create 1 placeholder group for final (players TBD based on rankings)
    INSERT INTO day_groups (match_day_id, group_number, player_ids, match_date)
    VALUES (v_matchday_id, 1, ARRAY[]::uuid[], v_current_date::date)
    RETURNING id INTO v_group_id;

    -- Create 3 rotations for final
    FOR v_rotation IN 1..3 LOOP
        INSERT INTO rotations (day_group_id, rotation_number)
        VALUES (v_group_id, v_rotation)
        RETURNING id INTO v_rotation_id;

        -- Create empty doubles match
        INSERT INTO doubles_matches (rotation_id, team1_player1_id, team1_player2_id, team2_player1_id, team2_player2_id)
        VALUES (v_rotation_id, NULL, NULL, NULL, NULL);
    END LOOP;

    RETURN json_build_object(
        'success', true,
        'matchDaysCreated', 7,
        'regularRounds', 5,
        'semifinals', 1,
        'final', 1,
        'totalMatches', 78,
        'datesApplied', v_matchday_dates IS NOT NULL
    );
END;
$function$;

-- Add comment explaining the updated function
COMMENT ON FUNCTION generate_league_calendar(uuid) IS 'Generates complete league calendar with 5 regular matchdays, semifinals, and final. Uses season matchday_dates if available. Semifinals and final groups are placeholders until rankings determine participants.';
