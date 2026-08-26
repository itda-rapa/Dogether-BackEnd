ALTER TABLE medical_support_revisions
    DROP CONSTRAINT ux_medical_support_revision_source_hash;

ALTER TABLE medical_support_revisions
    ADD CONSTRAINT ux_medical_support_revision_raw_parser
        UNIQUE (source_url, source_hash, parser_version);
