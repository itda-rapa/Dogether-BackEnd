package itda.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import itda.media.domain.StorageDeleteJobStatus;
import itda.media.repository.StorageDeleteJobRepository;
import org.junit.jupiter.api.Test;

class StorageDeleteJobMetricsTest {

    @Test
    void gaugesReadExistingDatabaseStateAtScrapeTime() {
        StorageDeleteJobRepository repository = mock(StorageDeleteJobRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(repository.countByStatusIn(anyCollection())).thenReturn(7L, 4L);
        when(repository.countByStatus(StorageDeleteJobStatus.FAILED)).thenReturn(2L, 3L);

        new StorageDeleteJobMetrics(registry, repository);

        assertThat(registry.get("storage.delete.jobs.backlog").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("storage.delete.jobs.failed").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("storage.delete.jobs.backlog").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("storage.delete.jobs.failed").gauge().value()).isEqualTo(3.0);
    }
}
