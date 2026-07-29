package itda.report.service;

import itda.report.domain.Report;
import itda.report.domain.ReportReason;
import itda.report.domain.ReportStatus;
import itda.report.dto.ReportResponse;
import itda.report.repository.ReportRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportCreateTransactionService {

    private final ReportRepository reportRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportService.CreateReportResult saveReport(
            Long reporterUserId, Long reporterPetId,
            Long reportedUserId, Long reportedPetId,
            Long roomId, ReportReason reasonCode, String detail) {
        Report report = new Report(reporterUserId, reporterPetId,
                reportedUserId, reportedPetId, roomId, reasonCode, detail);
        return new ReportService.CreateReportResult(
                ReportResponse.from(reportRepository.save(report)), true);
    }

    @Transactional(readOnly = true)
    public Optional<ReportService.CreateReportResult> findExistingOpenReport(
            Long reporterUserId, Long roomId) {
        return reportRepository
                .findByReporterUserIdAndRoomIdAndStatus(reporterUserId, roomId, ReportStatus.OPEN)
                .map(report -> new ReportService.CreateReportResult(
                        ReportResponse.from(report), false));
    }
}
