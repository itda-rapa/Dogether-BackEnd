CREATE UNIQUE INDEX ux_medical_support_revision_stable_semantic
    ON medical_support_revisions (source_organization, stable_source_program_id, semantic_fingerprint)
    WHERE stable_source_program_id IS NOT NULL;

CREATE UNIQUE INDEX ux_medical_support_revision_fallback_semantic
    ON medical_support_revisions (source_organization, region_scope, region_code, normalized_program_name, program_year, semantic_fingerprint)
    WHERE stable_source_program_id IS NULL;
