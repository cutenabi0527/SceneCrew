-- Existing hash-only links are preserved; only explicit rotation can replace them.
ALTER TABLE event ADD COLUMN invite_token_ciphertext TEXT;
ALTER TABLE event ADD CONSTRAINT ck_event_invite_ciphertext_hash
    CHECK (invite_token_ciphertext IS NULL OR invite_token_hash IS NOT NULL);
