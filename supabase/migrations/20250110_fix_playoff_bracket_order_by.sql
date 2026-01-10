-- Fix: ORDER BY must be inside json_agg, not outside when using aggregates

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
    -- Get semifinals groups with standings
    SELECT COALESCE(json_agg(group_data ORDER BY group_data->>'grp_num'), '[]'::json)
    INTO v_semifinals
    FROM (
        SELECT json_build_object(
            'group_id', dg.id,
            'grp_num', dg.group_number,
            'matchday', md.match_number,
            'standings', COALESCE((
                SELECT json_agg(
                    json_build_object(
                        'player_id', ps.player_id,
                        'player_name', COALESCE(lp.name, 'Unknown'),
                        'standing_position', ps.standing_position,
                        'points_for', ps.points_for,
                        'points_against', ps.points_against,
                        'games_won', ps.games_won,
                        'games_lost', ps.games_lost,
                        'is_manual_override', ps.is_manual_override,
                        'advances_to_next', ps.advances_to_next
                    ) ORDER BY ps.standing_position
                )
                FROM playoff_standings ps
                LEFT JOIN league_players lp ON lp.id = ps.player_id
                WHERE ps.day_group_id = dg.id
            ), '[]'::json),
            'ties', COALESCE((
                SELECT json_agg(
                    json_build_object(
                        'tie_position', tie_data.tie_position,
                        'player_ids', tie_data.player_ids,
                        'tied_points_for', tie_data.tied_points_for,
                        'tied_points_against', tie_data.tied_points_against,
                        'is_tie', true
                    )
                )
                FROM (
                    SELECT
                        MIN(ps.standing_position) as tie_position,
                        array_agg(ps.player_id::text ORDER BY ps.standing_position) as player_ids,
                        ps.points_for as tied_points_for,
                        ps.points_against as tied_points_against
                    FROM playoff_standings ps
                    WHERE ps.day_group_id = dg.id
                      AND ps.is_manual_override = false
                    GROUP BY ps.day_group_id, ps.points_for, ps.points_against
                    HAVING COUNT(*) > 1
                ) tie_data
            ), '[]'::json)
        ) as group_data
        FROM day_groups dg
        JOIN match_days md ON md.id = dg.match_day_id
        WHERE md.category_id = p_category_id
          AND md.match_number = 6
    ) subq;

    -- Get final groups with standings
    SELECT COALESCE(json_agg(group_data ORDER BY group_data->>'grp_num'), '[]'::json)
    INTO v_final
    FROM (
        SELECT json_build_object(
            'group_id', dg.id,
            'grp_num', dg.group_number,
            'matchday', md.match_number,
            'standings', COALESCE((
                SELECT json_agg(
                    json_build_object(
                        'player_id', ps.player_id,
                        'player_name', COALESCE(lp.name, 'Unknown'),
                        'standing_position', ps.standing_position,
                        'points_for', ps.points_for,
                        'points_against', ps.points_against,
                        'games_won', ps.games_won,
                        'games_lost', ps.games_lost,
                        'is_manual_override', ps.is_manual_override,
                        'advances_to_next', ps.advances_to_next
                    ) ORDER BY ps.standing_position
                )
                FROM playoff_standings ps
                LEFT JOIN league_players lp ON lp.id = ps.player_id
                WHERE ps.day_group_id = dg.id
            ), '[]'::json),
            'ties', COALESCE((
                SELECT json_agg(
                    json_build_object(
                        'tie_position', tie_data.tie_position,
                        'player_ids', tie_data.player_ids,
                        'tied_points_for', tie_data.tied_points_for,
                        'tied_points_against', tie_data.tied_points_against,
                        'is_tie', true
                    )
                )
                FROM (
                    SELECT
                        MIN(ps.standing_position) as tie_position,
                        array_agg(ps.player_id::text ORDER BY ps.standing_position) as player_ids,
                        ps.points_for as tied_points_for,
                        ps.points_against as tied_points_against
                    FROM playoff_standings ps
                    WHERE ps.day_group_id = dg.id
                      AND ps.is_manual_override = false
                    GROUP BY ps.day_group_id, ps.points_for, ps.points_against
                    HAVING COUNT(*) > 1
                ) tie_data
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
