package itda.report.service;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.report.domain.AdminAction;
import itda.report.domain.AdminActionType;
import itda.report.domain.Report;
import itda.report.domain.ReportStatus;
import itda.report.dto.AdminReportActionRequest;
import itda.report.dto.AdminReportDetailResponse;
import itda.report.dto.AdminReportMessageEvidence;
import itda.report.dto.AdminReportOffsetPage;
import itda.report.dto.AdminReportPageResponse;
import itda.report.dto.AdminReportPartyEvidence;
import itda.report.dto.AdminReportRoomEvidence;
import itda.report.dto.ReportResponse;
import itda.report.repository.AdminActionRepository;
import itda.report.repository.ReportRepository;
import itda.user.domain.Role;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private static final String REPORT_TARGET_TYPE = "REPORT";

    private final ReportRepository reportRepository;
    private final AdminActionRepository adminActionRepository;
    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;

    @Transactional(readOnly = true)
    public AdminReportPageResponse listReports(
            Long adminId,
            ReportStatus status,
            int page,
            int size
    ) {
        requireAdmin(adminId);
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );
        Page<Report> reports = status == null
                ? reportRepository.findAll(pageable)
                : reportRepository.findByStatus(status, pageable);
        return new AdminReportPageResponse(
                reports.getContent().stream().map(ReportResponse::from).toList(),
                new AdminReportOffsetPage(
                        reports.getNumber(),
                        reports.getSize(),
                        reports.getTotalElements(),
                        reports.getTotalPages()
                )
        );
    }

    @Transactional(readOnly = true)
    public AdminReportDetailResponse getReport(Long adminId, Long reportId) {
        requireAdmin(adminId);
        Report report = findReport(reportId);
        Pet reporterPet = findPet(report.getReporterPetId());
        Pet reportedPet = findPet(report.getReportedPetId());
        ChatRoom room = chatRoomRepository.findById(report.getRoomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Long> participantPetIds = participantRepository.findByRoomId(report.getRoomId())
                .stream()
                .map(participant -> participant.getPetId())
                .toList();
        List<AdminReportMessageEvidence> messages = messageRepository
                .findByRoomIdOrderByIdAsc(report.getRoomId())
                .stream()
                .map(AdminReportMessageEvidence::from)
                .toList();
        return new AdminReportDetailResponse(
                ReportResponse.from(report),
                AdminReportPartyEvidence.from(reporterPet),
                AdminReportPartyEvidence.from(reportedPet),
                AdminReportRoomEvidence.from(room, participantPetIds),
                messages
        );
    }

    @Transactional
    public ReportResponse resolveReport(
            Long adminId,
            Long reportId,
            AdminReportActionRequest request
    ) {
        requireAdmin(adminId);
        Report report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }

        ReportStatus resolvedStatus = request.actionType() == AdminActionType.DISMISSED
                ? ReportStatus.NO_ACTION
                : ReportStatus.ACTIONED;
        Instant resolvedAt = Instant.now();
        Map<String, Object> beforeState = reportState(report);
        report.resolve(resolvedStatus, adminId, resolvedAt, request.reason());
        Map<String, Object> afterState = reportState(report);
        adminActionRepository.save(AdminAction.forReport(
                adminId,
                reportId,
                request.actionType(),
                request.reason(),
                beforeState,
                afterState
        ));
        return ReportResponse.from(report);
    }

    private void requireAdmin(Long adminId) {
        boolean isAdmin = userRepository.findById(adminId)
                .map(user -> user.isActive()
                        && (user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN))
                .orElse(false);
        if (!isAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private Report findReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Pet findPet(Long petId) {
        return petRepository.findByIdWithOwner(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Map<String, Object> reportState(Report report) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", report.getStatus().name());
        state.put("reviewedByAdminId", report.getReviewedByAdminId());
        state.put("reviewedAt", report.getReviewedAt());
        state.put("resolutionNote", report.getResolutionNote());
        return state;
    }
}
