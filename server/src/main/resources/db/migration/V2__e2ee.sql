CREATE TABLE device_keys (
    device_id UUID PRIMARY KEY REFERENCES devices(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    registration_id INTEGER NOT NULL,
    identity_key BYTEA NOT NULL UNIQUE,
    signed_prekey_id INTEGER NOT NULL,
    signed_prekey_public BYTEA NOT NULL,
    signed_prekey_signature BYTEA NOT NULL,
    last_resort_kyber_id INTEGER NOT NULL,
    last_resort_kyber_public BYTEA NOT NULL,
    last_resort_kyber_signature BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE one_time_prekeys (
    device_id UUID NOT NULL REFERENCES devices(id),
    key_kind TEXT NOT NULL CHECK (key_kind IN ('EC', 'KYBER')),
    key_id INTEGER NOT NULL,
    public_key BYTEA NOT NULL,
    signature BYTEA,
    PRIMARY KEY(device_id, key_kind, key_id)
);

CREATE INDEX prekey_consumption ON one_time_prekeys(device_id, key_kind, key_id);

CREATE TABLE message_queue (
    message_id UUID NOT NULL,
    recipient_device_id UUID NOT NULL REFERENCES devices(id),
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    sender_device_id UUID NOT NULL REFERENCES devices(id),
    ciphertext_type INTEGER NOT NULL,
    ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    PRIMARY KEY(message_id, recipient_device_id)
);

CREATE INDEX message_recipient_queue
    ON message_queue(recipient_device_id, created_at)
    WHERE delivered_at IS NULL;
CREATE INDEX message_expiry ON message_queue(expires_at);
