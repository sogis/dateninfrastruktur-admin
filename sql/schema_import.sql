-- ili2pg 5.5.2 emits \n, \r and \t in ordinary PostgreSQL string literals.
-- Interpret them as control characters while building the generated CHECKs.
SET LOCAL standard_conforming_strings = off;
