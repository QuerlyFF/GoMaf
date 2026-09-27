-- Backfill-safe indexes and uniqueness required by the production application.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username_key ON users(username_key);
CREATE INDEX IF NOT EXISTS idx_game_players_game_id ON game_players(game_id);
CREATE INDEX IF NOT EXISTS idx_game_players_user_id ON game_players(user_id);
CREATE INDEX IF NOT EXISTS idx_games_game_date ON games(game_date);
CREATE INDEX IF NOT EXISTS idx_game_schedule_scheduled_at ON game_schedule(scheduled_at);
CREATE INDEX IF NOT EXISTS idx_users_registration_ip_hash_created_at ON users(registration_ip_hash, created_at);
