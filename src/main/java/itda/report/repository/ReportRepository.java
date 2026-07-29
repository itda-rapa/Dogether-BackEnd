package itda.report.repository;

import itda.report.domain.Report;
import itda.report.domain.ReportStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByReporterUserIdAndRoomIdAndStatus(
            Long reporterUserId, Long roomId, ReportStatus status);
}