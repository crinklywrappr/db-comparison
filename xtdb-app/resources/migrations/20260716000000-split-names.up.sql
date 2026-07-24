-- Split name into given_name + family_name.
--
-- (ragtime's SqlMigration runs statements without a transaction unless
-- :transactions is set — which suits XTDB's rule that queries may not
-- run inside a DML transaction.)
--
-- The only migration this app has: there was never a creation
-- migration because there is no schema to create. And note the
-- FOR ALL VALID_TIME: the rewrite covers the entire valid axis, so
-- every as-of view of *reality* — past, present, future — carries the
-- new shape. Only system-time archaeology ("what did the database
-- contain before this migration ran?") still shows the old rows.
--
-- (Splits on the first space; SUBSTRING/POSITION are XT 2.1 stdlib.
-- STRING_TO_ARRAY would be nicer but needs 2.2+.)
UPDATE employees FOR ALL VALID_TIME
SET given_name  = SUBSTRING(name FROM 1 FOR POSITION(' ' IN name) - 1),
    family_name = SUBSTRING(name FROM POSITION(' ' IN name) + 1),
    name = NULL
WHERE name IS NOT NULL
