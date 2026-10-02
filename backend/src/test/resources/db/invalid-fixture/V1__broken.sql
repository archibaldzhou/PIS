-- Both statements must roll back in PostgreSQL when the second fails.
CREATE TABLE must_not_survive (id bigint PRIMARY KEY);
SELECT 1 / 0;
