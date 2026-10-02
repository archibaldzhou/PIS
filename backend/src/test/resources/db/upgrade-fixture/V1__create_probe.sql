-- Synthetic migration fixture only; excluded from the application JAR.
CREATE TABLE migration_probe (
    id bigint PRIMARY KEY,
    value text NOT NULL
);
