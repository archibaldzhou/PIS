-- T05 structural template for synthetic data only; this is not a clinical workflow.
-- Provisional relationship: one request may produce several cases; each case belongs
-- to exactly one request. Unnumbered cases/containers are structural drafts, not
-- permission to enter clinical circulation. No clinical number generator is installed.

CREATE TABLE hospital (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL CHECK (code !~ '^[[:space:]]|[[:space:]]$' AND char_length(code) BETWEEN 1 AND 128),
    name text NOT NULL CHECK (name !~ '^[[:space:]]|[[:space:]]$' AND char_length(name) BETWEEN 1 AND 255),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_hospital_code UNIQUE (code)
);

CREATE TABLE source_system (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    code text NOT NULL CHECK (code !~ '^[[:space:]]|[[:space:]]$' AND char_length(code) BETWEEN 1 AND 128),
    name text NOT NULL CHECK (name !~ '^[[:space:]]|[[:space:]]$' AND char_length(name) BETWEEN 1 AND 255),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_source_system_code UNIQUE (hospital_id, code),
    CONSTRAINT uq_source_system_hospital_id UNIQUE (hospital_id, id)
);

CREATE TABLE department (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    code text NOT NULL CHECK (code !~ '^[[:space:]]|[[:space:]]$' AND char_length(code) BETWEEN 1 AND 128),
    name text NOT NULL CHECK (name !~ '^[[:space:]]|[[:space:]]$' AND char_length(name) BETWEEN 1 AND 255),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_department_code UNIQUE (hospital_id, code),
    CONSTRAINT uq_department_hospital_id UNIQUE (hospital_id, id)
);

CREATE TABLE patient (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    display_name text CHECK (display_name !~ '^[[:space:]]|[[:space:]]$' AND char_length(display_name) BETWEEN 1 AND 255),
    birth_date date,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_patient_hospital_id UNIQUE (hospital_id, id)
);

CREATE TABLE patient_identifier (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    patient_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    identifier_namespace text NOT NULL CHECK (identifier_namespace !~ '^[[:space:]]|[[:space:]]$' AND char_length(identifier_namespace) BETWEEN 1 AND 128),
    identifier_value text NOT NULL CHECK (identifier_value !~ '^[[:space:]]|[[:space:]]$' AND char_length(identifier_value) BETWEEN 1 AND 255),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_identifier_patient FOREIGN KEY (hospital_id, patient_id)
        REFERENCES patient(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_identifier_source FOREIGN KEY (hospital_id, source_system_id)
        REFERENCES source_system(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_patient_identifier_value UNIQUE (hospital_id, source_system_id, identifier_namespace, identifier_value)
);
CREATE INDEX ix_patient_identifier_patient ON patient_identifier (hospital_id, patient_id);

CREATE TABLE encounter (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    patient_id uuid NOT NULL,
    source_system_id uuid NOT NULL,
    encounter_number text NOT NULL CHECK (encounter_number !~ '^[[:space:]]|[[:space:]]$' AND char_length(encounter_number) BETWEEN 1 AND 255),
    department_id uuid,
    occurred_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_encounter_patient FOREIGN KEY (hospital_id, patient_id)
        REFERENCES patient(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounter_source FOREIGN KEY (hospital_id, source_system_id)
        REFERENCES source_system(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounter_department FOREIGN KEY (hospital_id, department_id)
        REFERENCES department(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_encounter_number UNIQUE (hospital_id, source_system_id, encounter_number),
    CONSTRAINT uq_encounter_patient_id UNIQUE (hospital_id, patient_id, id)
);
CREATE INDEX ix_encounter_department ON encounter (hospital_id, department_id);

CREATE TABLE pathology_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    patient_id uuid NOT NULL,
    encounter_id uuid,
    source_system_id uuid NOT NULL,
    request_number text NOT NULL CHECK (request_number !~ '^[[:space:]]|[[:space:]]$' AND char_length(request_number) BETWEEN 1 AND 255),
    requesting_department_id uuid,
    requested_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pathology_request_patient FOREIGN KEY (hospital_id, patient_id)
        REFERENCES patient(hospital_id, id) ON DELETE RESTRICT,
    -- MATCH SIMPLE permits a missing encounter while the independent patient FK remains active.
    CONSTRAINT fk_pathology_request_encounter FOREIGN KEY (hospital_id, patient_id, encounter_id)
        REFERENCES encounter(hospital_id, patient_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pathology_request_source FOREIGN KEY (hospital_id, source_system_id)
        REFERENCES source_system(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pathology_request_department FOREIGN KEY (hospital_id, requesting_department_id)
        REFERENCES department(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_pathology_request_number UNIQUE (hospital_id, source_system_id, request_number),
    CONSTRAINT uq_pathology_request_hospital_id UNIQUE (hospital_id, id)
);
CREATE INDEX ix_pathology_request_patient_encounter ON pathology_request (hospital_id, patient_id, encounter_id);
CREATE INDEX ix_pathology_request_department ON pathology_request (hospital_id, requesting_department_id);

CREATE TABLE pathology_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    request_id uuid NOT NULL,
    number_namespace text CHECK (number_namespace !~ '^[[:space:]]|[[:space:]]$' AND char_length(number_namespace) BETWEEN 1 AND 128),
    case_number text CHECK (case_number !~ '^[[:space:]]|[[:space:]]$' AND char_length(case_number) BETWEEN 1 AND 255),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_pathology_case_number_pair CHECK ((number_namespace IS NULL) = (case_number IS NULL)),
    CONSTRAINT fk_pathology_case_request FOREIGN KEY (hospital_id, request_id)
        REFERENCES pathology_request(hospital_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_pathology_case_number UNIQUE (hospital_id, number_namespace, case_number),
    CONSTRAINT uq_pathology_case_request_id UNIQUE (hospital_id, request_id, id)
);

CREATE TABLE specimen_container (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id uuid NOT NULL REFERENCES hospital(id) ON DELETE RESTRICT,
    request_id uuid NOT NULL,
    case_id uuid,
    number_namespace text CHECK (number_namespace !~ '^[[:space:]]|[[:space:]]$' AND char_length(number_namespace) BETWEEN 1 AND 128),
    container_number text CHECK (container_number !~ '^[[:space:]]|[[:space:]]$' AND char_length(container_number) BETWEEN 1 AND 255),
    label text CHECK (label !~ '^[[:space:]]|[[:space:]]$' AND char_length(label) BETWEEN 1 AND 255),
    collected_at timestamptz,
    received_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_specimen_container_number_pair CHECK ((number_namespace IS NULL) = (container_number IS NULL)),
    CONSTRAINT fk_specimen_container_request FOREIGN KEY (hospital_id, request_id)
        REFERENCES pathology_request(hospital_id, id) ON DELETE RESTRICT,
    -- A container may be unassigned; assignment must stay within its request and hospital.
    CONSTRAINT fk_specimen_container_case FOREIGN KEY (hospital_id, request_id, case_id)
        REFERENCES pathology_case(hospital_id, request_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_specimen_container_number UNIQUE (hospital_id, number_namespace, container_number)
);
CREATE INDEX ix_specimen_container_request_case ON specimen_container (hospital_id, request_id, case_id);

COMMENT ON TABLE patient IS 'Hospital-local identity; identifiers and demographics are not immutable primary keys';
COMMENT ON TABLE patient_identifier IS 'Source/namespace-scoped identifier; no automatic identity matching or merging';
COMMENT ON TABLE pathology_case IS 'Provisional one-request-to-many-cases template; unnumbered rows are structural drafts only';
COMMENT ON TABLE specimen_container IS 'Submitted physical container, not a tissue block or slide; unnumbered rows are structural drafts only';
COMMENT ON COLUMN pathology_case.number_namespace IS 'Explicit numbering scope; no clinical format or allocation policy is assumed';
COMMENT ON COLUMN specimen_container.number_namespace IS 'Explicit label numbering scope; not an assumed barcode standard';
