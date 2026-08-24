package itda.risk.repository;

import itda.risk.domain.RiskSignalOutbox;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskSignalOutboxRepository extends JpaRepository<RiskSignalOutbox, Long> {
    boolean existsByEventId(UUID eventId);
}
