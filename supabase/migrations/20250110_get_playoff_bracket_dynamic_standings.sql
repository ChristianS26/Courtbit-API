-- Fix: Calculate standings dynamically from match results instead of relying on playoff_standings table

CREATE OR REPLACE FUNCTION get_playoff_bracket(p_category_id uuid)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_result json;
    v_semifinals json;
    v_final json;
BEGIN
    -- Get semifinals groups with dynamically calculated standings
    SELECT COALESCE(json_agg(group_data ORDER BY (group_data->>'grp_num')::int), '[]'::json)
    INTO v_semifinals
    FROM (
        SELECT json_build_object(
            'group_id', dg.id,
            'grp_num', dg.group_number,
            'matchday', md.match_number,
            'standings', COALESCE((
                -- Calculate standings from match results
                SELECT json_agg(
                    json_build_object(
                        'player_id', player_stats.player_id,
                        'player_name', player_stats.player_name,
                        'standing_position', ROW_NUMBER() OVER (
                            ORDER BY player_stats.games_won DESC,
                                     (player_stats.points_for - player_stats.points_against) DESC,
                                     player_stats.points_for DESC
                        ),
                        'points_for', player_stats.points_for,
                        'points_against', player_stats.points_against,
                        'games_won', player_stats.games_won,
                        'games_lost', player_stats.games_lost,
                        'point_diff', player_stats.points_for - player_stats.points_against
                    ) ORDER BY player_stats.games_won DESC,
                               (player_stats.points_for - player_stats.points_against) DESC,
                               player_stats.points_for DESC
                )
                FROM (
                    SELECT
                        dgp.player_id,
                        COALESCE(lp.name, 'Unknown') as player_name,
                        COALESCE(SUM(
                            CASE
                                WHEN (dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id)
                                     AND dm.score_team1 > dm.score_team2 THEN 1
                                WHEN (dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id)
                                     AND dm.score_team2 > dm.score_team1 THEN 1
                                ELSE 0
                            END
                        ), 0) as games_won,
                        COALESCE(SUM(
                            CASE
                                WHEN (dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id)
                                     AND dm.score_team1 < dm.score_team2 THEN 1
                                WHEN (dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id)
                                     AND dm.score_team2 < dm.score_team1 THEN 1
                                ELSE 0
                            END
                        ), 0) as games_lost,
                        COALESCE(SUM(
                            CASE
                                WHEN dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id THEN dm.score_team1
                                WHEN dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id THEN dm.score_team2
                                ELSE 0
                            END
                        ), 0) as points_for,
                        COALESCE(SUM(
                            CASE
                                WHEN dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id THEN dm.score_team2
                                WHEN dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id THEN dm.score_team1
                                ELSE 0
                            END
                        ), 0) as points_against
                    FROM day_group_players dgp
                    LEFT JOIN league_players lp ON lp.id = dgp.player_id
                    LEFT JOIN rotations r ON r.day_group_id = dgp.day_group_id
                    LEFT JOIN doubles_matches dm ON dm.rotation_id = r.id
                        AND (dm.player1_id = dgp.player_id
                             OR dm.player2_id = dgp.player_id
                             OR dm.player3_id = dgp.player_id
                             OR dm.player4_id = dgp.player_id)
                        AND dm.score_team1 IS NOT NULL
                    WHERE dgp.day_group_id = dg.id
                    GROUP BY dgp.player_id, lp.name
                ) player_stats
            ), '[]'::json)
        ) as group_data
        FROM day_groups dg
        JOIN match_days md ON md.id = dg.match_day_id
        WHERE md.category_id = p_category_id
          AND md.match_number = 6
    ) subq;

    -- Get final groups with dynamically calculated standings
    SELECT COALESCE(json_agg(group_data ORDER BY (group_data->>'grp_num')::int), '[]'::json)
    INTO v_final
    FROM (
        SELECT json_build_object(
            'group_id', dg.id,
            'grp_num', dg.group_number,
            'matchday', md.match_number,
            'standings', COALESCE((
                SELECT json_agg(
                    json_build_object(
                        'player_id', player_stats.player_id,
                        'player_name', player_stats.player_name,
                        'standing_position', ROW_NUMBER() OVER (
                            ORDER BY player_stats.games_won DESC,
                                     (player_stats.points_for - player_stats.points_against) DESC,
                                     player_stats.points_for DESC
                        ),
                        'points_for', player_stats.points_for,
                        'points_against', player_stats.points_against,
                        'games_won', player_stats.games_won,
                        'games_lost', player_stats.games_lost,
                        'point_diff', player_stats.points_for - player_stats.points_against
                    ) ORDER BY player_stats.games_won DESC,
                               (player_stats.points_for - player_stats.points_against) DESC,
                               player_stats.points_for DESC
                )
                FROM (
                    SELECT
                        dgp.player_id,
                        COALESCE(lp.name, 'Unknown') as player_name,
                        COALESCE(SUM(
                            CASE
                                WHEN (dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id)
                                     AND dm.score_team1 > dm.score_team2 THEN 1
                                WHEN (dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id)
                                     AND dm.score_team2 > dm.score_team1 THEN 1
                                ELSE 0
                            END
                        ), 0) as games_won,
                        COALESCE(SUM(
                            CASE
                                WHEN (dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id)
                                     AND dm.score_team1 < dm.score_team2 THEN 1
                                WHEN (dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id)
                                     AND dm.score_team2 < dm.score_team1 THEN 1
                                ELSE 0
                            END
                        ), 0) as games_lost,
                        COALESCE(SUM(
                            CASE
                                WHEN dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id THEN dm.score_team1
                                WHEN dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id THEN dm.score_team2
                                ELSE 0
                            END
                        ), 0) as points_for,
                        COALESCE(SUM(
                            CASE
                                WHEN dm.player1_id = dgp.player_id OR dm.player2_id = dgp.player_id THEN dm.score_team2
                                WHEN dm.player3_id = dgp.player_id OR dm.player4_id = dgp.player_id THEN dm.score_team1
                                ELSE 0
                            END
                        ), 0) as points_against
                    FROM day_group_players dgp
                    LEFT JOIN league_players lp ON lp.id = dgp.player_id
                    LEFT JOIN rotations r ON r.day_group_id = dgp.day_group_id
                    LEFT JOIN doubles_matches dm ON dm.rotation_id = r.id
                        AND (dm.player1_id = dgp.player_id
                             OR dm.player2_id = dgp.player_id
                             OR dm.player3_id = dgp.player_id
                             OR dm.player4_id = dgp.player_id)
                        AND dm.score_team1 IS NOT NULL
                    WHERE dgp.day_group_id = dg.id
                    GROUP BY dgp.player_id, lp.name
                ) player_stats
            ), '[]'::json)
        ) as group_data
        FROM day_groups dg
        JOIN match_days md ON md.id = dg.match_day_id
        WHERE md.category_id = p_category_id
          AND md.match_number = 7
    ) subq;

    -- Build final result
    v_result := json_build_object(
        'categoryId', p_category_id,
        'semifinals', v_semifinals,
        'final', v_final
    );

    RETURN v_result;
END;
$function$;
