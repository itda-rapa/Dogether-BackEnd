CREATE INDEX IF NOT EXISTS idx_network_link_source
    ON network_link (source);

CREATE INDEX IF NOT EXISTS idx_network_link_target
    ON network_link (target);
