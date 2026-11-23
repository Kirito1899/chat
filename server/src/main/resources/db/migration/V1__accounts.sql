CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    username TEXT NOT NULL,
    username_normalized TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invites (
    id UUID PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    consumed_by UUID REFERENCES accounts(id)
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX one_active_device_per_account
    ON devices(account_id)
    WHERE revoked_at IS NULL;

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    device_id UUID NOT NULL REFERENCES devices(id),
    access_token_hash TEXT NOT NULL UNIQUE,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX sessions_access_lookup ON sessions(access_token_hash) WHERE revoked_at IS NULL;
CREATE INDEX sessions_refresh_lookup ON sessions(refresh_token_hash) WHERE revoked_at IS NULL;

CREATE TABLE used_refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id),
    used_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE email_challenges (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    purpose TEXT NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RECOVERY')),
    code_hmac TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX active_email_challenge
    ON email_challenges(account_id, purpose, created_at DESC)
    WHERE consumed_at IS NULL;
