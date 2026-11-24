CREATE TABLE groups (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES groups(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY(group_id, account_id)
);

CREATE INDEX active_group_members ON group_members(group_id, account_id) WHERE removed_at IS NULL;
CREATE INDEX account_groups ON group_members(account_id, group_id) WHERE removed_at IS NULL;

CREATE TABLE group_messages (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id),
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    sender_device_id UUID NOT NULL REFERENCES devices(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE message_queue ADD COLUMN group_id UUID REFERENCES groups(id);
ALTER TABLE message_queue ADD COLUMN group_message_id UUID REFERENCES group_messages(id);

CREATE UNIQUE INDEX group_envelope_unique
    ON message_queue(group_id, group_message_id, recipient_device_id)
    WHERE group_id IS NOT NULL;
CREATE INDEX group_recipient_purge ON message_queue(group_id, recipient_device_id);

CREATE TABLE media_objects (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts(id),
    expected_size BIGINT NOT NULL,
    uploaded_size BIGINT NOT NULL DEFAULT 0,
    ciphertext_sha256 TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'UPLOADING' CHECK (status IN ('UPLOADING', 'READY', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX media_expiry ON media_objects(expires_at) WHERE deleted_at IS NULL;

ALTER TABLE message_queue ADD COLUMN media_id UUID REFERENCES media_objects(id);

CREATE TABLE media_recipients (
    media_id UUID NOT NULL REFERENCES media_objects(id),
    device_id UUID NOT NULL REFERENCES devices(id),
    group_message_id UUID REFERENCES group_messages(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(media_id, device_id)
);
