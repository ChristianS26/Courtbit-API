-- Add per-stat adjustment columns to league_adjustments table
-- These allow organizers to directly adjust individual player stats

ALTER TABLE league_adjustments
ADD COLUMN IF NOT EXISTS points_for_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS points_against_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS games_won_adj INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS games_lost_adj INTEGER DEFAULT 0;

-- Add comment explaining the columns
COMMENT ON COLUMN league_adjustments.points_for_adj IS 'Adjustment to player points scored (positive or negative)';
COMMENT ON COLUMN league_adjustments.points_against_adj IS 'Adjustment to player points conceded (positive or negative)';
COMMENT ON COLUMN league_adjustments.games_won_adj IS 'Adjustment to games won count (positive or negative)';
COMMENT ON COLUMN league_adjustments.games_lost_adj IS 'Adjustment to games lost count (positive or negative)';

-- Update calculate_league_rankings function to include stat adjustments
CREATE OR REPLACE FUNCTION public.calculate_league_rankings(p_category_id uuid)
RETURNS TABLE(
    player_id uuid,
    user_uid text,
    player_name text,
    photo_url text,
    points_for integer,
    points_against integer,
    games_won integer,
    games_lost integer,
    adjustment integer,
    adjusted_points_for integer,
    point_diff integer,
    adjusted_diff integer,
    rank integer
)
LANGUAGE plpgsql
SET search_path TO ''
AS $function$
DECLARE
    v_ranking_criteria text[];
    v_max_points integer;
    v_forfeit_winner_points integer;
    v_forfeit_loser_points integer;
    v_order_clause text;
    v_criterion text;
BEGIN
    -- Get ranking criteria, max_points, and forfeit points from the season (via category)
    SELECT
        s.ranking_criteria,
        COALESCE(s.max_points_per_game, 6),
        COALESCE(s.forfeit_winner_points, 15),
        COALESCE(s.forfeit_loser_points, 12)
    INTO v_ranking_criteria, v_max_points, v_forfeit_winner_points, v_forfeit_loser_points
    FROM public.seasons s
    JOIN public.league_categories lc ON lc.season_id = s.id
    WHERE lc.id = p_category_id;

    -- Default if null
    IF v_ranking_criteria IS NULL THEN
        v_ranking_criteria := ARRAY['adjusted_points', 'point_diff', 'games_won'];
    END IF;
    IF v_max_points IS NULL THEN
        v_max_points := 6;
    END IF;
    IF v_forfeit_winner_points IS NULL THEN
        v_forfeit_winner_points := 15;
    END IF;
    IF v_forfeit_loser_points IS NULL THEN
        v_forfeit_loser_points := 12;
    END IF;

    -- Build dynamic ORDER BY clause
    v_order_clause := '';
    FOREACH v_criterion IN ARRAY v_ranking_criteria
    LOOP
        IF v_order_clause != '' THEN
            v_order_clause := v_order_clause || ', ';
        END IF;

        CASE v_criterion
            WHEN 'adjusted_points' THEN
                v_order_clause := v_order_clause || 'adjusted_pf DESC';
            WHEN 'point_diff' THEN
                v_order_clause := v_order_clause || 'adj_diff DESC';
            WHEN 'games_won' THEN
                v_order_clause := v_order_clause || 'final_won DESC';
            WHEN 'games_lost' THEN
                v_order_clause := v_order_clause || 'final_lost ASC';
            WHEN 'points_for' THEN
                v_order_clause := v_order_clause || 'final_pf DESC';
            WHEN 'points_against' THEN
                v_order_clause := v_order_clause || 'final_pa ASC';
            ELSE
                -- Skip unknown criteria
                v_order_clause := RTRIM(v_order_clause, ', ');
        END CASE;
    END LOOP;

    -- Add alphabetical as final tiebreaker
    IF v_order_clause != '' THEN
        v_order_clause := v_order_clause || ', ';
    END IF;
    v_order_clause := v_order_clause || 'player_name ASC';

    RETURN QUERY EXECUTE format('
        WITH player_stats AS (
            SELECT
                p.id AS player_id,
                p.user_uid AS user_uid,
                p.name AS player_name,
                u.photo_url AS photo_url,
                COALESCE(SUM(
                    CASE
                        WHEN dm.is_forfeit = true THEN
                            CASE
                                WHEN p.id = ANY(dm.forfeited_player_ids) THEN 0
                                ELSE %s
                            END
                        WHEN dm.team1_player1_id = p.id OR dm.team1_player2_id = p.id THEN dm.score_team1
                        WHEN dm.team2_player1_id = p.id OR dm.team2_player2_id = p.id THEN dm.score_team2
                        ELSE 0
                    END
                ), 0)::integer AS pf,
                COALESCE(SUM(
                    CASE
                        WHEN dm.is_forfeit = true THEN
                            CASE
                                WHEN p.id = ANY(dm.forfeited_player_ids) THEN %s
                                ELSE 0
                            END
                        WHEN dm.team1_player1_id = p.id OR dm.team1_player2_id = p.id THEN dm.score_team2
                        WHEN dm.team2_player1_id = p.id OR dm.team2_player2_id = p.id THEN dm.score_team1
                        ELSE 0
                    END
                ), 0)::integer AS pa,
                COALESCE(SUM(
                    CASE
                        WHEN dm.is_forfeit = true THEN
                            CASE
                                WHEN p.id = ANY(dm.forfeited_player_ids) THEN 0
                                ELSE 1
                            END
                        WHEN (dm.team1_player1_id = p.id OR dm.team1_player2_id = p.id) AND dm.score_team1 = %s THEN 1
                        WHEN (dm.team2_player1_id = p.id OR dm.team2_player2_id = p.id) AND dm.score_team2 = %s THEN 1
                        ELSE 0
                    END
                ), 0)::integer AS won,
                COALESCE(SUM(
                    CASE
                        WHEN dm.is_forfeit = true THEN
                            CASE
                                WHEN p.id = ANY(dm.forfeited_player_ids) THEN 1
                                ELSE 0
                            END
                        WHEN (dm.team1_player1_id = p.id OR dm.team1_player2_id = p.id) AND dm.score_team2 = %s THEN 1
                        WHEN (dm.team2_player1_id = p.id OR dm.team2_player2_id = p.id) AND dm.score_team1 = %s THEN 1
                        ELSE 0
                    END
                ), 0)::integer AS lost
            FROM public.league_players p
            LEFT JOIN public.users u ON u.uid = p.user_uid
            LEFT JOIN public.doubles_matches dm ON (
                dm.team1_player1_id = p.id OR dm.team1_player2_id = p.id OR
                dm.team2_player1_id = p.id OR dm.team2_player2_id = p.id
            ) AND (
                (dm.score_team1 IS NOT NULL AND dm.score_team2 IS NOT NULL) OR dm.is_forfeit = true
            )
            LEFT JOIN public.rotations r ON r.id = dm.rotation_id
            LEFT JOIN public.day_groups dg ON dg.id = r.day_group_id
            LEFT JOIN public.match_days md ON md.id = dg.match_day_id
            WHERE p.category_id = %L
              AND p.is_waiting_list = false
              AND (md.match_number IS NULL OR md.match_number <= 5)
            GROUP BY p.id, p.user_uid, p.name, u.photo_url
        ),
        player_adjustments AS (
            SELECT
                adj.player_id,
                COALESCE(SUM(adj.value), 0)::integer AS total_adj,
                COALESCE(SUM(COALESCE(adj.points_for_adj, 0)), 0)::integer AS total_pf_adj,
                COALESCE(SUM(COALESCE(adj.points_against_adj, 0)), 0)::integer AS total_pa_adj,
                COALESCE(SUM(COALESCE(adj.games_won_adj, 0)), 0)::integer AS total_won_adj,
                COALESCE(SUM(COALESCE(adj.games_lost_adj, 0)), 0)::integer AS total_lost_adj
            FROM public.league_adjustments adj
            JOIN public.league_players lp ON lp.id = adj.player_id
            WHERE lp.category_id = %L
            GROUP BY adj.player_id
        ),
        combined AS (
            SELECT
                ps.player_id,
                ps.user_uid,
                ps.player_name,
                ps.photo_url,
                -- Base stats (for display - these show computed values from matches)
                ps.pf,
                ps.pa,
                ps.won,
                ps.lost,
                -- Final stats with all adjustments applied
                (ps.pf + COALESCE(pa.total_pf_adj, 0))::integer AS final_pf,
                (ps.pa + COALESCE(pa.total_pa_adj, 0))::integer AS final_pa,
                (ps.won + COALESCE(pa.total_won_adj, 0))::integer AS final_won,
                (ps.lost + COALESCE(pa.total_lost_adj, 0))::integer AS final_lost,
                -- Points adjustment (legacy field)
                COALESCE(pa.total_adj, 0) AS adj,
                -- Adjusted points for (includes both legacy adjustment AND points_for_adj)
                (ps.pf + COALESCE(pa.total_adj, 0) + COALESCE(pa.total_pf_adj, 0))::integer AS adjusted_pf,
                -- Point diff (using final adjusted stats)
                ((ps.pf + COALESCE(pa.total_pf_adj, 0)) - (ps.pa + COALESCE(pa.total_pa_adj, 0)))::integer AS diff,
                -- Adjusted diff (including legacy points adjustment)
                ((ps.pf + COALESCE(pa.total_adj, 0) + COALESCE(pa.total_pf_adj, 0)) - (ps.pa + COALESCE(pa.total_pa_adj, 0)))::integer AS adj_diff
            FROM player_stats ps
            LEFT JOIN player_adjustments pa ON pa.player_id = ps.player_id
        ),
        ranked AS (
            SELECT
                c.player_id,
                c.user_uid,
                c.player_name,
                c.photo_url,
                c.final_pf AS points_for,
                c.final_pa AS points_against,
                c.final_won AS games_won,
                c.final_lost AS games_lost,
                c.adj AS adjustment,
                c.adjusted_pf AS adjusted_points_for,
                c.diff AS point_diff,
                c.adj_diff AS adjusted_diff,
                ROW_NUMBER() OVER (ORDER BY %s)::integer AS rank
            FROM combined c
        )
        SELECT * FROM ranked
    ',
    v_forfeit_winner_points,
    v_forfeit_winner_points,
    v_max_points, v_max_points, v_max_points, v_max_points,
    p_category_id, p_category_id,
    v_order_clause);
END;
$function$;
