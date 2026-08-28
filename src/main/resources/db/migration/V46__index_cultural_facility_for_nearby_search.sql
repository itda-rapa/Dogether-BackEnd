UPDATE cultural_facility
SET geom = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)
WHERE geom IS NULL
  AND longitude IS NOT NULL
  AND latitude IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cultural_facility_category
    ON cultural_facility (category);

CREATE INDEX IF NOT EXISTS idx_cultural_facility_geography
    ON cultural_facility
    USING GIST ((geom::geography));
