-- Fix: Calculate standings dynamically using player_ids array and proper subquery structure

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
                SELECT json_agg(
                    json_build_object(
                        'player_id', ranked.player_id,
                        'player_name', ranked.player_name,
                        'standing_position', ranked.standing_position,
                        'points_for', ranked.points_for,
                        'points_against', ranked.points_against,
                        'games_won', ranked.games_won,
                        'games_lost', ranked.games_lost,
                        'point_diff', ranked.point_diff
                    ) ORDER BY ranked.standing_position
                )
                FROM (
                    SELECT
                        player_stats.*,
                        ROW_NUMBER() OVER (
                            ORDER BY player_stats.games_won DESC,
                                     player_stats.point_diff DESC,
                                     player_stats.points_for DESC
                        ) as standing_position
                    FROM (
                        SELECT
                            p.player_id,
                            COALESCE(lp.name, 'Unknown') as player_name,
                            COALESCE(SUM(
                                CASE
                                    WHEN (dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id)
                                         AND dm.score_team1 > dm.score_team2 THEN 1
                                    WHEN (dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id)
                                         AND dm.score_team2 > dm.score_team1 THEN 1
                                    ELSE 0
                                END
                            ), 0)::int as games_won,
                            COALESCE(SUM(
                                CASE
                                    WHEN (dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id)
                                         AND dm.score_team1 < dm.score_team2 THEN 1
                                    WHEN (dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id)
                                         AND dm.score_team2 < dm.score_team1 THEN 1
                                    ELSE 0
                                END
                            ), 0)::int as games_lost,
                            COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team1
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team2
                                    ELSE 0
                                END
                            ), 0)::int as points_for,
                            COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team2
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team1
                                    ELSE 0
                                END
                            ), 0)::int as points_against,
                            (COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team1
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team2
                                    ELSE 0
                                END
                            ), 0) - COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team2
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team1
                                    ELSE 0
                                END
                            ), 0))::int as point_diff
                        FROM unnest(dg.player_ids) AS p(player_id)
                        LEFT JOIN league_players lp ON lp.id = p.player_id
                        LEFT JOIN rotations r ON r.day_group_id = dg.id
                        LEFT JOIN doubles_matches dm ON dm.rotation_id = r.id
                            AND (dm.team1_player1_id = p.player_id
                                 OR dm.team1_player2_id = p.player_id
                                 OR dm.team2_player1_id = p.player_id
                                 OR dm.team2_player2_id = p.player_id)
                            AND dm.score_team1 IS NOT NULL
                        GROUP BY p.player_id, lp.name
                    ) player_stats
                ) ranked
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
                        'player_id', ranked.player_id,
                        'player_name', ranked.player_name,
                        'standing_position', ranked.standing_position,
                        'points_for', ranked.points_for,
                        'points_against', ranked.points_against,
                        'games_won', ranked.games_won,
                        'games_lost', ranked.games_lost,
                        'point_diff', ranked.point_diff
                    ) ORDER BY ranked.standing_position
                )
                FROM (
                    SELECT
                        player_stats.*,
                        ROW_NUMBER() OVER (
                            ORDER BY player_stats.games_won DESC,
                                     player_stats.point_diff DESC,
                                     player_stats.points_for DESC
                        ) as standing_position
                    FROM (
                        SELECT
                            p.player_id,
                            COALESCE(lp.name, 'Unknown') as player_name,
                            COALESCE(SUM(
                                CASE
                                    WHEN (dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id)
                                         AND dm.score_team1 > dm.score_team2 THEN 1
                                    WHEN (dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id)
                                         AND dm.score_team2 > dm.score_team1 THEN 1
                                    ELSE 0
                                END
                            ), 0)::int as games_won,
                            COALESCE(SUM(
                                CASE
                                    WHEN (dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id)
                                         AND dm.score_team1 < dm.score_team2 THEN 1
                                    WHEN (dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id)
                                         AND dm.score_team2 < dm.score_team1 THEN 1
                                    ELSE 0
                                END
                            ), 0)::int as games_lost,
                            COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team1
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team2
                                    ELSE 0
                                END
                            ), 0)::int as points_for,
                            COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team2
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team1
                                    ELSE 0
                                END
                            ), 0)::int as points_against,
                            (COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team1
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team2
                                    ELSE 0
                                END
                            ), 0) - COALESCE(SUM(
                                CASE
                                    WHEN dm.team1_player1_id = p.player_id OR dm.team1_player2_id = p.player_id THEN dm.score_team2
                                    WHEN dm.team2_player1_id = p.player_id OR dm.team2_player2_id = p.player_id THEN dm.score_team1
                                    ELSE 0
                                END
                            ), 0))::int as point_diff
                        FROM unnest(dg.player_ids) AS p(player_id)
                        LEFT JOIN league_players lp ON lp.id = p.player_id
                        LEFT JOIN rotations r ON r.day_group_id = dg.id
                        LEFT JOIN doubles_matches dm ON dm.rotation_id = r.id
                            AND (dm.team1_player1_id = p.player_id
                                 OR dm.team1_player2_id = p.player_id
                                 OR dm.team2_player1_id = p.player_id
                                 OR dm.team2_player2_id = p.player_id)
                            AND dm.score_team1 IS NOT NULL
                        GROUP BY p.player_id, lp.name
                    ) player_stats
                ) ranked
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
