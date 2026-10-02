-- T06 identity and resource-authorization structure for local synthetic testing.
-- No users, passwords, hospitals, grants, qualifications or case assignments are seeded.
-- Templates are illustrative permissions, not clinical staffing or signing privileges.

ALTER TABLE pathology_case
    ADD CONSTRAINT uq_pathology_case_hospital_id UNIQUE (hospital_id, id);

CREATE TABLE campus (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    code text NOT NULL CHECK (code !~ '^[[:space:]]|[[:space:]]$' AND char_length(code) BETWEEN 1 AND 128),
    name text NOT NULL CHECK (name !~ '^[[:space:]]|[[:space:]]$' AND char_length(name) BETWEEN 1 AND 255),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_campus_code UNIQUE (hospital_id, code),
    CONSTRAINT uq_campus_hospital_id UNIQUE (hospital_id, id)
);

CREATE TABLE department_campus (
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    campus_id uuid NOT NULL,
    department_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (hospital_id, campus_id, department_id),
    CONSTRAINT fk_department_campus_campus FOREIGN KEY (hospital_id, campus_id)
        REFERENCES campus(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_department_campus_department FOREIGN KEY (hospital_id, department_id)
        REFERENCES department(hospital_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_department_campus_department ON department_campus (hospital_id, department_id);

CREATE TABLE app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Store canonical lowercase ASCII usernames; login normalization uses the same form.
    username text NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    display_name text NOT NULL CHECK (display_name !~ '^[[:space:]]|[[:space:]]$' AND char_length(display_name) BETWEEN 1 AND 255),
    password_hash text NOT NULL CHECK (password_hash !~ '^[[:space:]]|[[:space:]]$' AND char_length(password_hash) BETWEEN 1 AND 1024),
    enabled boolean NOT NULL DEFAULT false,
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    synthetic_only boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE security_role (
    code text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    name text NOT NULL CHECK (name !~ '^[[:space:]]|[[:space:]]$' AND char_length(name) BETWEEN 1 AND 255),
    enabled boolean NOT NULL DEFAULT true,
    is_template boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE security_role_permission (
    role_code text NOT NULL REFERENCES security_role(code) ON DELETE RESTRICT,
    permission_code text NOT NULL CHECK (permission_code IN ('CASE_READ', 'CASE_EDIT')),
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE user_role_scope (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role_code text NOT NULL REFERENCES security_role(code) ON DELETE RESTRICT,
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    campus_id uuid,
    department_id uuid,
    scope_kind text NOT NULL CHECK (scope_kind IN ('HOSPITAL', 'CAMPUS', 'DEPARTMENT')),
    case_filter text NOT NULL CHECK (case_filter IN ('ALL_IN_SCOPE', 'ASSIGNED_ONLY')),
    valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_role_scope_shape CHECK (
        (scope_kind = 'HOSPITAL' AND campus_id IS NULL AND department_id IS NULL)
        OR (scope_kind = 'CAMPUS' AND campus_id IS NOT NULL AND department_id IS NULL)
        OR (scope_kind = 'DEPARTMENT' AND campus_id IS NOT NULL AND department_id IS NOT NULL)),
    CONSTRAINT ck_user_role_scope_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_user_role_scope_campus FOREIGN KEY (hospital_id, campus_id)
        REFERENCES campus(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_scope_department FOREIGN KEY (hospital_id, department_id)
        REFERENCES department(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_scope_department_campus FOREIGN KEY (hospital_id, campus_id, department_id)
        REFERENCES department_campus(hospital_id, campus_id, department_id) ON DELETE RESTRICT
);
CREATE INDEX ix_user_role_scope_lookup ON user_role_scope (user_id, hospital_id, role_code) WHERE revoked_at IS NULL;

CREATE TABLE case_access_scope (
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    case_id uuid NOT NULL,
    campus_id uuid NOT NULL,
    owning_department_id uuid NOT NULL,
    scope_version bigint NOT NULL DEFAULT 0 CHECK (scope_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (hospital_id, case_id),
    CONSTRAINT fk_case_access_scope_case FOREIGN KEY (hospital_id, case_id)
        REFERENCES pathology_case(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_access_scope_department_campus FOREIGN KEY (hospital_id, campus_id, owning_department_id)
        REFERENCES department_campus(hospital_id, campus_id, department_id) ON DELETE RESTRICT
);
CREATE INDEX ix_case_access_scope_case ON case_access_scope (case_id);
CREATE INDEX ix_case_access_scope_ownership ON case_access_scope (hospital_id, campus_id, owning_department_id);

CREATE TABLE case_assignment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL,
    case_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    access_level text NOT NULL CHECK (access_level IN ('READ', 'EDIT')),
    scope_version bigint NOT NULL CHECK (scope_version >= 0),
    valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_case_assignment_scope FOREIGN KEY (hospital_id, case_id)
        REFERENCES case_access_scope(hospital_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT ck_case_assignment_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX ix_case_assignment_lookup ON case_assignment (user_id, hospital_id, case_id, scope_version) WHERE revoked_at IS NULL;

CREATE TABLE user_operation_qualification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    operation_code text NOT NULL CHECK (operation_code = 'CASE_EDIT'),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    campus_id uuid,
    department_id uuid,
    scope_kind text NOT NULL CHECK (scope_kind IN ('HOSPITAL', 'CAMPUS', 'DEPARTMENT')),
    valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_operation_qualification_shape CHECK (
        (scope_kind = 'HOSPITAL' AND campus_id IS NULL AND department_id IS NULL)
        OR (scope_kind = 'CAMPUS' AND campus_id IS NOT NULL AND department_id IS NULL)
        OR (scope_kind = 'DEPARTMENT' AND campus_id IS NOT NULL AND department_id IS NOT NULL)),
    CONSTRAINT ck_user_operation_qualification_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_user_operation_qualification_campus FOREIGN KEY (hospital_id, campus_id)
        REFERENCES campus(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_operation_qualification_department FOREIGN KEY (hospital_id, department_id)
        REFERENCES department(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_operation_qualification_department_campus FOREIGN KEY (hospital_id, campus_id, department_id)
        REFERENCES department_campus(hospital_id, campus_id, department_id) ON DELETE RESTRICT
);
CREATE INDEX ix_user_operation_qualification_lookup ON user_operation_qualification (user_id, hospital_id, operation_code) WHERE revoked_at IS NULL;

CREATE FUNCTION maintain_app_user_auth_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.auth_version IS NULL OR NEW.auth_version < OLD.auth_version THEN
        RAISE EXCEPTION 'auth_version must not decrease or become null' USING ERRCODE = '23514';
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.enabled IS DISTINCT FROM OLD.enabled THEN
        NEW.auth_version := GREATEST(NEW.auth_version, OLD.auth_version + 1);
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_app_user_auth_version BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION maintain_app_user_auth_version();

CREATE FUNCTION maintain_case_scope_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.hospital_id IS DISTINCT FROM OLD.hospital_id OR NEW.case_id IS DISTINCT FROM OLD.case_id THEN
        RAISE EXCEPTION 'case scope identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.scope_version IS NULL OR NEW.scope_version < OLD.scope_version THEN
        RAISE EXCEPTION 'scope_version must not decrease or become null' USING ERRCODE = '23514';
    END IF;
    IF NEW.campus_id IS DISTINCT FROM OLD.campus_id OR NEW.owning_department_id IS DISTINCT FROM OLD.owning_department_id THEN
        NEW.scope_version := GREATEST(NEW.scope_version, OLD.scope_version + 1);
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_case_scope_version BEFORE UPDATE ON case_access_scope
    FOR EACH ROW EXECUTE FUNCTION maintain_case_scope_version();

INSERT INTO security_role (code, name, is_template) VALUES
    ('SECURITY_ADMIN_TEMPLATE', 'Synthetic security administrator template', true),
    ('CASE_READER_TEMPLATE', 'Synthetic case reader template', true),
    ('CASE_EDITOR_TEMPLATE', 'Synthetic case editor template', true);
INSERT INTO security_role_permission (role_code, permission_code) VALUES
    ('CASE_READER_TEMPLATE', 'CASE_READ'),
    ('CASE_EDITOR_TEMPLATE', 'CASE_READ'),
    ('CASE_EDITOR_TEMPLATE', 'CASE_EDIT');

COMMENT ON TABLE user_role_scope IS 'One role grant and its own resource scope/filter must match together; never splice separate grants';
COMMENT ON TABLE case_access_scope IS 'Authoritative current ownership; no inference from request, encounter or requesting department';
COMMENT ON TABLE case_assignment IS 'Assignment is not a role; snapshot scope_version becomes stale after ownership transfer';
COMMENT ON TABLE user_operation_qualification IS 'Synthetic CASE_EDIT qualification skeleton only; never a clinical signing credential';
COMMENT ON COLUMN app_user.synthetic_only IS 'Marks synthetic accounts; not evidence of a production-ready identity provisioning workflow';
