package itda.report.repository;

import itda.report.domain.Report;
import itda.report.domain.ReportStatus;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByReporterUserIdAndRoomIdAndStatus(
            Long reporterUserId, Long roomId, ReportStatus status);

    boolean existsByRoomId(Long roomId);

    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.id = :reportId")
    Optional<Report> findByIdForUpdate(@Param("reportId") Long reportId);
}
