-- A legacy installation must register again before it can receive push notifications.
ALTER TABLE device_installation ADD COLUMN push_session_id UUID;
