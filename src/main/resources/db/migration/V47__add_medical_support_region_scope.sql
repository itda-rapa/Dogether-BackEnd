ALTER TABLE medical_support_programs
    ADD COLUMN region_scope VARCHAR(20);

ALTER TABLE medical_support_revisions
    ADD COLUMN region_scope VARCHAR(20);

UPDATE medical_support_programs
SET region_scope = CASE
    WHEN region_sigungu_name IS NULL THEN 'SIDO'
    ELSE 'SIGUNGU'
END
WHERE region_scope IS NULL;

UPDATE medical_support_revisions
SET region_scope = CASE
    WHEN region_sigungu_name IS NULL THEN 'SIDO'
    ELSE 'SIGUNGU'
END
WHERE region_scope IS NULL;

ALTER TABLE medical_support_programs
    ALTER COLUMN region_scope SET NOT NULL;

ALTER TABLE medical_support_revisions
    ADD CONSTRAINT ck_medical_support_revision_region_scope
        CHECK (region_scope IN ('SIDO', 'SIGUNGU'));

ALTER TABLE medical_support_revisions
    ALTER COLUMN region_scope SET NOT NULL;

ALTER TABLE medical_support_programs
    ADD CONSTRAINT ck_medical_support_program_region_scope
        CHECK (region_scope IN ('SIDO', 'SIGUNGU'));

DROP INDEX ux_medical_support_program_fallback_identity;

CREATE UNIQUE INDEX ux_medical_support_program_fallback_identity
    ON medical_support_programs (
        source_organization,
        region_scope,
        region_code,
        normalized_program_name,
        program_year
    )
    WHERE stable_source_program_id IS NULL;

DROP INDEX ix_medical_support_program_region;

CREATE INDEX ix_medical_support_program_region
    ON medical_support_programs (region_scope, region_code, id);
