-- T07 successful command metadata. No clinical payloads, users or permissions are seeded.
-- Migrations run under a separate deployment owner. Runtime must not possess these credentials.
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    actor_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    actor_auth_version bigint NOT NULL CHECK (actor_auth_version >= 0),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    operation_code text NOT NULL CHECK (operation_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    resource_type text NOT NULL CHECK (resource_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    resource_id uuid NOT NULL,
    previous_version bigint CHECK (previous_version >= 0),
    result_version bigint NOT NULL CHECK (result_version >= 0),
    trace_id uuid NOT NULL,
    CONSTRAINT ck_audit_version_progression CHECK (previous_version IS NULL OR result_version > previous_version)
);
CREATE INDEX ix_audit_resource ON audit_event (hospital_id, resource_type, resource_id, occurred_at, id);
CREATE INDEX ix_audit_actor_time ON audit_event (actor_user_id, occurred_at, id);

CREATE TABLE idempotency_command (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    operation_code text NOT NULL CHECK (operation_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    key_hash bytea NOT NULL CHECK (octet_length(key_hash) = 32),
    request_digest bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    digest_version smallint NOT NULL CHECK (digest_version = 1),
    state text NOT NULL CHECK (state IN ('IN_PROGRESS', 'SUCCEEDED')),
    response_status integer,
    resource_type text CHECK (resource_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    resource_id uuid,
    result_version bigint CHECK (result_version >= 0),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    completed_at timestamptz,
    CONSTRAINT uq_idempotency_command UNIQUE (actor_user_id, hospital_id, operation_code, key_hash),
    CONSTRAINT ck_idempotency_result CHECK (
        (state = 'IN_PROGRESS' AND response_status IS NULL AND resource_type IS NULL AND resource_id IS NULL
            AND result_version IS NULL AND completed_at IS NULL)
        OR (state = 'SUCCEEDED' AND response_status IS NOT NULL AND response_status IN (200, 201) AND resource_type IS NOT NULL
            AND resource_id IS NOT NULL AND result_version IS NOT NULL AND completed_at IS NOT NULL))
);
COMMENT ON TABLE audit_event IS 'Successful mutation metadata; runtime INSERT-only is a deployment privilege boundary, not DBA-proof immutability';
COMMENT ON TABLE idempotency_command IS 'Reservation, business mutation, success audit and bounded receipt commit together; no independently committed reservation or automatic retention policy';
