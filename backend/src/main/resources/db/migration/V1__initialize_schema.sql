-- Flyway creates the configured application schema before this migration.
-- Business tables belong to later tasks; never edit an applied migration.
COMMENT ON SCHEMA "${flyway:defaultSchema}" IS 'PIS application schema';
