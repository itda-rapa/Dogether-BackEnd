package itda.risk.repository;

import itda.risk.domain.RiskSignalOutbox;
import itda.risk.domain.RiskSignalOutboxStatus;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskSignalOutboxRepository extends JpaRepository<RiskSignalOutbox, Long> {
    boolean existsByEventId(UUID eventId);

    long countByStatus(RiskSignalOutboxStatus status);

    long countByStatusIn(Collection<RiskSignalOutboxStatus> statuses);
}
