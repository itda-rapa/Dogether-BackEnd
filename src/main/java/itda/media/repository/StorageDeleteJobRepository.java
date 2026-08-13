package itda.media.repository;

import itda.media.domain.StorageDeleteJob;
import org.springframework.data.jpa.repository.JpaRepository;
import itda.media.domain.StorageDeleteJobStatus;
import java.util.Collection;

public interface StorageDeleteJobRepository extends JpaRepository<StorageDeleteJob, Long> {
    boolean existsByObjectKey(String objectKey);
    long countByStatusIn(Collection<StorageDeleteJobStatus> statuses);
    long countByStatus(StorageDeleteJobStatus status);
}
