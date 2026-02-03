-- Migration: Add tournament_type and organizer_name to user registrations RPC functions
-- This allows the iOS app to display tournament type and organizer in My Tournaments view

-- Drop existing functions first to change return type
DROP FUNCTION IF EXISTS get_user_registrations(TEXT, TEXT, INT, INT);
DROP FUNCTION IF EXISTS get_user_registrations_in_tournament(TEXT, UUID);

-- Recreate get_user_registrations function with tournament_type and organizer_name
CREATE OR REPLACE FUNCTION get_user_registrations(
    p_user_uid TEXT,
    p_status TEXT DEFAULT NULL,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    team_id UUID,
    tournament_id UUID,
    tournament_name TEXT,
    tournament_start DATE,
    tournament_end DATE,
    tournament_status TEXT,
    tournament_type TEXT,
    organizer_name TEXT,
    category_id INT,
    category_name TEXT,
    i_am_player_a BOOLEAN,
    paid_by_me BOOLEAN,
    paid_by_partner BOOLEAN,
    partner_uid TEXT,
    partner_first_name TEXT,
    partner_last_name TEXT,
    partner_photo_url TEXT,
    partner_phone TEXT,
    partner_gender TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    RETURN QUERY
    SELECT
        t.id AS team_id,
        t.tournament_id,
        tr.name AS tournament_name,
        tr.start_date AS tournament_start,
        tr.end_date AS tournament_end,
        tr.status AS tournament_status,
        tr.type AS tournament_type,
        o.display_name AS organizer_name,
        t.category_id,
        c.name AS category_name,
        (t.player_a_uid = p_user_uid) AS i_am_player_a,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_a_paid
            ELSE t.player_b_paid
        END AS paid_by_me,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_b_paid
            ELSE t.player_a_paid
        END AS paid_by_partner,
        -- Partner info (the other player)
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_b_uid
            ELSE t.player_a_uid
        END AS partner_uid,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN COALESCE(ub.first_name, t.player_b_name)
            ELSE COALESCE(ua.first_name, t.player_a_name)
        END AS partner_first_name,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.last_name
            ELSE ua.last_name
        END AS partner_last_name,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.photo_url
            ELSE ua.photo_url
        END AS partner_photo_url,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN COALESCE(ub.phone, t.player_b_phone)
            ELSE COALESCE(ua.phone, t.player_a_phone)
        END AS partner_phone,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.gender
            ELSE ua.gender
        END AS partner_gender
    FROM teams t
    INNER JOIN tournaments tr ON tr.id = t.tournament_id
    INNER JOIN categories c ON c.id = t.category_id
    LEFT JOIN organizers o ON o.id = tr.organizer_id
    LEFT JOIN users ua ON ua.uid = t.player_a_uid
    LEFT JOIN users ub ON ub.uid = t.player_b_uid
    WHERE (t.player_a_uid = p_user_uid OR t.player_b_uid = p_user_uid)
    AND (p_status IS NULL OR tr.status = p_status)
    ORDER BY tr.start_date DESC
    LIMIT p_limit
    OFFSET p_offset;
END;
$$;

-- Update get_user_registrations_in_tournament function to include tournament_type and organizer_name
CREATE OR REPLACE FUNCTION get_user_registrations_in_tournament(
    p_user_uid TEXT,
    p_tournament_id UUID
)
RETURNS TABLE (
    team_id UUID,
    tournament_id UUID,
    tournament_name TEXT,
    tournament_start DATE,
    tournament_end DATE,
    tournament_status TEXT,
    tournament_type TEXT,
    organizer_name TEXT,
    category_id INT,
    category_name TEXT,
    i_am_player_a BOOLEAN,
    paid_by_me BOOLEAN,
    paid_by_partner BOOLEAN,
    partner_uid TEXT,
    partner_first_name TEXT,
    partner_last_name TEXT,
    partner_photo_url TEXT,
    partner_phone TEXT,
    partner_gender TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    RETURN QUERY
    SELECT
        t.id AS team_id,
        t.tournament_id,
        tr.name AS tournament_name,
        tr.start_date AS tournament_start,
        tr.end_date AS tournament_end,
        tr.status AS tournament_status,
        tr.type AS tournament_type,
        o.display_name AS organizer_name,
        t.category_id,
        c.name AS category_name,
        (t.player_a_uid = p_user_uid) AS i_am_player_a,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_a_paid
            ELSE t.player_b_paid
        END AS paid_by_me,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_b_paid
            ELSE t.player_a_paid
        END AS paid_by_partner,
        -- Partner info (the other player)
        CASE
            WHEN t.player_a_uid = p_user_uid THEN t.player_b_uid
            ELSE t.player_a_uid
        END AS partner_uid,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN COALESCE(ub.first_name, t.player_b_name)
            ELSE COALESCE(ua.first_name, t.player_a_name)
        END AS partner_first_name,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.last_name
            ELSE ua.last_name
        END AS partner_last_name,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.photo_url
            ELSE ua.photo_url
        END AS partner_photo_url,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN COALESCE(ub.phone, t.player_b_phone)
            ELSE COALESCE(ua.phone, t.player_a_phone)
        END AS partner_phone,
        CASE
            WHEN t.player_a_uid = p_user_uid THEN ub.gender
            ELSE ua.gender
        END AS partner_gender
    FROM teams t
    INNER JOIN tournaments tr ON tr.id = t.tournament_id
    INNER JOIN categories c ON c.id = t.category_id
    LEFT JOIN organizers o ON o.id = tr.organizer_id
    LEFT JOIN users ua ON ua.uid = t.player_a_uid
    LEFT JOIN users ub ON ub.uid = t.player_b_uid
    WHERE t.tournament_id = p_tournament_id
    AND (t.player_a_uid = p_user_uid OR t.player_b_uid = p_user_uid);
END;
$$;
