-- 날짜 또는 시각 중 하나만 추출된 약속 초안도 해당 단서를 잃지 않도록 보존한다.
ALTER TABLE card_drafts
    ADD COLUMN extracted_date VARCHAR(10),
    ADD COLUMN extracted_time VARCHAR(5);

ALTER TABLE card_drafts
    ADD CONSTRAINT ck_card_draft_extracted_date
        CHECK (extracted_date IS NULL OR extracted_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    ADD CONSTRAINT ck_card_draft_extracted_time
        CHECK (extracted_time IS NULL OR extracted_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$');
