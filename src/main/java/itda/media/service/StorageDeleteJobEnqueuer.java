package itda.media.service;

import itda.media.domain.StorageDeleteReason;
import java.time.Instant;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StorageDeleteJobEnqueuer {
    private final JdbcTemplate jdbc;

    public void enqueue(String key, String versionId, StorageDeleteReason reason, Instant eligibleAt) {
        jdbc.update("""
                insert into storage_delete_jobs
                    (object_key, object_version_id, reason, status, attempts,
                     next_retry_at)
                values (?, ?, ?, 'PENDING', 0, ?)
                on conflict (object_key, reason) do nothing
                """, key, versionId, reason.name(), Timestamp.from(eligibleAt));
    }
}
