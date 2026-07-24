UPDATE employees FOR ALL VALID_TIME
SET name = given_name || ' ' || family_name,
    given_name = NULL,
    family_name = NULL
WHERE given_name IS NOT NULL
