UPDATE animal_hospital
SET geom = ST_SetSRID(ST_MakePoint(x_longitude, y_latitude), 4326)
WHERE geom IS NULL
  AND x_longitude BETWEEN -180 AND 180
  AND y_latitude BETWEEN -90 AND 90;

UPDATE animal_pharmacy
SET geom = ST_SetSRID(ST_MakePoint(x_longitude, y_latitude), 4326)
WHERE geom IS NULL
  AND x_longitude BETWEEN -180 AND 180
  AND y_latitude BETWEEN -90 AND 90;

CREATE INDEX IF NOT EXISTS ix_animal_hospital_geom_geography
    ON animal_hospital USING GIST ((geom::geography));

CREATE INDEX IF NOT EXISTS ix_animal_pharmacy_geom_geography
    ON animal_pharmacy USING GIST ((geom::geography));
