INSERT INTO neighborhoods (
    code,
    sido_name,
    sigungu_name,
    eupmyeondong_name,
    active
) VALUES
    ('4113111500', '경기도', '성남시 수정구', '시흥동', TRUE),
    ('4113111600', '경기도', '성남시 수정구', '금토동', TRUE),
    ('4113111700', '경기도', '성남시 수정구', '사송동', TRUE)
ON CONFLICT (code) DO UPDATE
SET sido_name = EXCLUDED.sido_name,
    sigungu_name = EXCLUDED.sigungu_name,
    eupmyeondong_name = EXCLUDED.eupmyeondong_name,
    active = EXCLUDED.active,
    updated_at = CURRENT_TIMESTAMP;
