-- Opt-in synthetic workflow. No organizations, accounts or grants are seeded.
CREATE TABLE workflow_scope (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id),
    campus_id uuid NOT NULL,
    department_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    enabled boolean NOT NULL DEFAULT false,
    UNIQUE (hospital_id, id),
    UNIQUE (hospital_id, campus_id, department_id, source_system_id),
    FOREIGN KEY (hospital_id, campus_id, department_id) REFERENCES department_campus(hospital_id, campus_id, department_id),
    FOREIGN KEY (hospital_id, source_system_id) REFERENCES source_system(hospital_id, id)
);
CREATE FUNCTION protect_workflow_scope_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.hospital_id, NEW.campus_id, NEW.department_id, NEW.source_system_id)
        IS DISTINCT FROM ROW(OLD.id, OLD.hospital_id, OLD.campus_id, OLD.department_id, OLD.source_system_id) THEN
        RAISE EXCEPTION 'Workflow scope identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_workflow_scope_identity BEFORE UPDATE ON workflow_scope
    FOR EACH ROW EXECUTE FUNCTION protect_workflow_scope_identity();
CREATE TABLE workflow_grant (
    user_id uuid NOT NULL REFERENCES app_user(id),
    scope_id uuid NOT NULL REFERENCES workflow_scope(id),
    can_read boolean NOT NULL DEFAULT false,
    can_write boolean NOT NULL DEFAULT false CHECK (NOT can_write OR can_read),
    valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until timestamptz,
    revoked_at timestamptz,
    PRIMARY KEY (user_id, scope_id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE TABLE request_workflow (
    request_id uuid PRIMARY KEY,
    hospital_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('DRAFT', 'SUBMITTED')),
    clinical_history text NOT NULL DEFAULT '' CHECK (char_length(clinical_history) <= 4000),
    sampled_at timestamptz,
    business_type text NOT NULL DEFAULT 'ROUTINE' CHECK (business_type = 'ROUTINE'),
    created_by uuid NOT NULL REFERENCES app_user(id),
    submitted_by uuid REFERENCES app_user(id),
    submitted_at timestamptz,
    FOREIGN KEY (hospital_id, request_id) REFERENCES pathology_request(hospital_id, id),
    FOREIGN KEY (hospital_id, scope_id) REFERENCES workflow_scope(hospital_id, id),
    CHECK ((state = 'DRAFT' AND submitted_by IS NULL AND submitted_at IS NULL)
        OR (state = 'SUBMITTED' AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL))
);
CREATE INDEX ix_request_workflow_scope ON request_workflow(scope_id, state, request_id);
CREATE TABLE request_container_detail (
    container_id uuid PRIMARY KEY REFERENCES specimen_container(id),
    site text NOT NULL CHECK (char_length(site) BETWEEN 1 AND 255 AND btrim(site) <> ''),
    laterality text NOT NULL CHECK (laterality IN ('NONE', 'LEFT', 'RIGHT', 'BILATERAL', 'UNKNOWN')),
    material_quantity integer NOT NULL CHECK (material_quantity BETWEEN 1 AND 999),
    fixative text NOT NULL DEFAULT '' CHECK (char_length(fixative) <= 128),
    fixed_at timestamptz
);
