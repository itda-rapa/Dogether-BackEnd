DROP INDEX IF EXISTS ix_risk_signal_outbox_claim;

CREATE INDEX ix_risk_signal_outbox_due
    ON risk_signal_outbox (next_retry_at, id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX ix_risk_signal_outbox_stale_claim
    ON risk_signal_outbox (claimed_at, id)
    WHERE status = 'PROCESSING';
