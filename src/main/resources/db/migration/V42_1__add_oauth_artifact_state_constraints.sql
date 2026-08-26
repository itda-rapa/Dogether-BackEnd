ALTER TABLE oauth_login_codes
    ADD CONSTRAINT ck_oauth_login_codes_state_consumed_at
        CHECK (
            (status = 'AVAILABLE' AND consumed_at IS NULL)
            OR (status = 'CONSUMED' AND consumed_at IS NOT NULL)
        );

ALTER TABLE oauth_signup_tokens
    ADD CONSTRAINT ck_oauth_signup_tokens_state_consumed_at
        CHECK (
            (status = 'AVAILABLE' AND consumed_at IS NULL)
            OR (status = 'CONSUMED' AND consumed_at IS NOT NULL)
        );
