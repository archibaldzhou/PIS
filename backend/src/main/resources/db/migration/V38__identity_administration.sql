-- Explicitly provisioned synthetic administration; no users/passwords/grants are seeded.
ALTER TABLE app_user ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;
ALTER TABLE workflow_scope ADD COLUMN admin_version bigint NOT NULL DEFAULT 0 CHECK(admin_version>=0);

CREATE TABLE identity_account (
 user_id uuid PRIMARY KEY REFERENCES app_user(id),
 hospital_id uuid NOT NULL REFERENCES hospital(id),
 employee_number text NOT NULL CHECK(char_length(employee_number) BETWEEN 1 AND 64 AND btrim(employee_number)=employee_number),
 default_scope_id uuid,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(hospital_id,user_id), UNIQUE(hospital_id,employee_number),
 FOREIGN KEY(hospital_id,default_scope_id) REFERENCES workflow_scope(hospital_id,id)
);
CREATE TABLE identity_admin_grant (
 user_id uuid NOT NULL REFERENCES app_user(id), hospital_id uuid NOT NULL REFERENCES hospital(id),
 valid_until timestamptz NOT NULL, revoked_at timestamptz,
 PRIMARY KEY(user_id,hospital_id)
);
CREATE TABLE identity_scope_assignment (
 user_id uuid NOT NULL, hospital_id uuid NOT NULL, scope_id uuid NOT NULL,
 roles text[] NOT NULL, permissions text[] NOT NULL,
 qualification_verified boolean NOT NULL DEFAULT false,
 valid_until timestamptz NOT NULL,
 PRIMARY KEY(user_id,scope_id),
 FOREIGN KEY(hospital_id,user_id) REFERENCES identity_account(hospital_id,user_id),
 FOREIGN KEY(hospital_id,scope_id) REFERENCES workflow_scope(hospital_id,id),
 FOREIGN KEY(user_id,scope_id) REFERENCES workflow_grant(user_id,scope_id),
 CHECK(cardinality(roles) BETWEEN 0 AND 20), CHECK(cardinality(permissions) BETWEEN 0 AND 80)
);
ALTER TABLE identity_account ADD CONSTRAINT fk_identity_default_assignment
 FOREIGN KEY(user_id,default_scope_id) REFERENCES identity_scope_assignment(user_id,scope_id)
 DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE identity_change_event (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL REFERENCES hospital(id),
 actor_id uuid NOT NULL REFERENCES app_user(id), target_id uuid NOT NULL,
 action text NOT NULL, reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 1000 AND btrim(reason)<>''),
 change_set jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(change_set)='object'),
 result_version bigint NOT NULL CHECK(result_version>=0),
 occurred_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX ix_identity_change_hospital ON identity_change_event(hospital_id,occurred_at DESC,id);
CREATE TRIGGER trg_identity_change_immutable BEFORE UPDATE OR DELETE ON identity_change_event
 FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();

-- Independent denied-command journal: no FK locks against the failed transaction.
CREATE TABLE identity_denial_event (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, actor_id uuid NOT NULL,
 error_code text NOT NULL CHECK(error_code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
 trace_id uuid NOT NULL, occurred_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX ix_identity_denial_hospital ON identity_denial_event(hospital_id,occurred_at DESC,id);
CREATE TRIGGER trg_identity_denial_immutable BEFORE UPDATE OR DELETE ON identity_denial_event
 FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();

CREATE TABLE report_output_grant (
 user_id uuid NOT NULL REFERENCES app_user(id), scope_id uuid NOT NULL REFERENCES workflow_scope(id),
 qualification text NOT NULL CHECK(qualification='SYN-REPORT-OUTPUT-1'),
 valid_until timestamptz NOT NULL, revoked_at timestamptz, PRIMARY KEY(user_id,scope_id)
);
