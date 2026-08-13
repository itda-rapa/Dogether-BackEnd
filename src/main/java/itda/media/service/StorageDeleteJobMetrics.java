package itda.media.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import itda.media.domain.StorageDeleteJobStatus;
import itda.media.repository.StorageDeleteJobRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StorageDeleteJobMetrics {
    private static final List<StorageDeleteJobStatus> BACKLOG = List.of(
            StorageDeleteJobStatus.PENDING,
            StorageDeleteJobStatus.PROCESSING,
            StorageDeleteJobStatus.RETRY
    );

    public StorageDeleteJobMetrics(
            MeterRegistry registry,
            StorageDeleteJobRepository repository
    ) {
        Gauge.builder("storage.delete.jobs.backlog", repository,
                        source -> source.countByStatusIn(BACKLOG))
                .description("Storage deletion jobs waiting or in progress")
                .register(registry);
        Gauge.builder("storage.delete.jobs.failed", repository,
                        source -> source.countByStatus(StorageDeleteJobStatus.FAILED))
                .description("Storage deletion jobs requiring manual attention")
                .register(registry);
    }
}
