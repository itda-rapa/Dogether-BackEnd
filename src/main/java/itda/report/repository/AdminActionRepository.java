package itda.report.repository;

import itda.report.domain.AdminAction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    List<AdminAction> findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(
            String targetType,
            Long targetId
    );
}
