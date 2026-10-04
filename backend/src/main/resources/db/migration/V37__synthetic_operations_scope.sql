-- Explicit read-only operations qualification; no accounts or grants are seeded.
CREATE TABLE operations_grant (
 user_id uuid NOT NULL REFERENCES app_user(id),
 scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 qualification text NOT NULL CHECK(qualification='SYN-OPS-1'),
 valid_until timestamptz NOT NULL,
 revoked_at timestamptz,
 PRIMARY KEY(user_id,scope_id)
);
