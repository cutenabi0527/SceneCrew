-- Legacy registrations have no trustworthy participation revision and cannot receive pushes.
ALTER TABLE device_token ADD COLUMN participation_revision BIGINT;

CREATE TABLE device_installation (
    device_id UUID PRIMARY KEY,
    proof_hash BYTEA NOT NULL CHECK (octet_length(proof_hash) = 32),
    account_id UUID NOT NULL REFERENCES account(id),
    token_hash BYTEA UNIQUE CHECK (octet_length(token_hash) = 32)
);

CREATE INDEX ix_device_installation_account ON device_installation(account_id);
CREATE INDEX ix_device_token_device ON device_token(device_id);
