-- Query-plan indexes for public list and ordering hot paths.
-- V1-V7 remain immutable.

CREATE INDEX IF NOT EXISTS idx_users_public_rating_username
    ON users (rating DESC, username ASC)
    WHERE account_role IN ('PLAYER', 'MODERATOR');

CREATE INDEX IF NOT EXISTS idx_games_public_date_id
    ON games (game_date DESC, id DESC)
    WHERE hidden_from_public = FALSE;

CREATE INDEX IF NOT EXISTS idx_games_tournament_date_id
    ON games (tournament_id, game_date ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_news_created_at_desc
    ON news (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tournaments_created_at_desc
    ON tournaments (created_at DESC);
