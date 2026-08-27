ALTER TABLE users
    ADD COLUMN weight_kg NUMERIC NULL,
    ADD CONSTRAINT ck_users_weight_kg
        CHECK (
            weight_kg IS NULL
            OR (
                weight_kg >= 1.00
                AND weight_kg <= 500.00
                AND scale(weight_kg) <= 2
            )
        );
