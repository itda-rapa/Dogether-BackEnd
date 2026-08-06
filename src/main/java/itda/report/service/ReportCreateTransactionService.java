package itda.report.service;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
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
    private final ChatRoomRepository chatRoomRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportService.CreateReportResult saveReport(
            Long reporterUserId, Long reporterPetId,
            Long reportedUserId, Long reportedPetId,
            Long roomId, ReportReason reasonCode, String detail) {
        // Report 삽입과 같은 트랜잭션(REQUIRES_NEW)에서 채팅방 행에 SELECT FOR UPDATE lock을
        // 획득해 동일 방에 대한 동시 신고 생성을 직렬화한다.
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
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
