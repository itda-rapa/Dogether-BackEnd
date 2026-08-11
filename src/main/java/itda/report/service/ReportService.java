package itda.report.service;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.report.domain.ReportReason;
import itda.report.domain.ReportStatus;
import itda.report.dto.ReportCreateRequest;
import itda.report.dto.ReportResponse;
import itda.report.repository.ReportRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final ActivePetQueryService activePetQueryService;
    private final ChatRoomParticipantRepository chatRoomParticipantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final PetDisplayQueryService petDisplayQueryService;
    private final ReportCreateTransactionService reportCreateTransactionService;

    public record CreateReportResult(ReportResponse report, boolean created) {}

    /**
     * 신고 접수. 실제 DB 쓰기는 ReportCreateTransactionService에 위임하고,
     * 이 메서드 자체는 트랜잭션을 열지 않는다(propagation = NEVER).
     * 동시 요청 경합에서 DataIntegrityViolationException이 발생하면
     * 새 트랜잭션에서 기존 OPEN 신고를 재조회한다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public CreateReportResult createReport(Long userId, ReportCreateRequest request) {
        // 1. ActivePet 확인
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 2. 상세 사유 길이 검증
        if (request.detail() != null && request.detail().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // 3. OTHER 사유에 대한 detail 필수 검증
        if (request.reasonCode() == ReportReason.OTHER
                && (request.detail() == null || request.detail().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // 4. 방 참가자 검증 (채팅과 동일한 규칙: left_at IS NULL 필수)
        if (!chatRoomParticipantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                request.roomId(), actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        // 5. 채팅방 조회
        ChatRoom room = chatRoomRepository.findById(request.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // 6. DIRECT 방 검증 — GROUP 방은 신고 대상이 아님
        Long petLowId = room.getPetLowId();
        Long petHighId = room.getPetHighId();
        if (petLowId == null || petHighId == null) {
            throw new BusinessException(ErrorCode.REPORT_ROOM_REQUIRED);
        }
        if (!petLowId.equals(actor.petId()) && !petHighId.equals(actor.petId())) {
            // 참가자 테이블이 오염되더라도 DIRECT pair 밖의 Pet이 상대를 신고하지 못하게 한다.
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        Long reportedPetId = petLowId.equals(actor.petId()) ? petHighId : petLowId;

        // 7. 피신고자 조회 — PetDisplayQueryService를 통해 Pet 존재 확인과 소유자 정보를 함께 얻음
        PetDisplaySummary reportedPet;
        try {
            reportedPet = petDisplayQueryService.getPetDisplaySummary(reportedPetId);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.PET_NOT_FOUND) {
                throw e;
            }
            // PET_NOT_FOUND만 CHAT_ROOM_NOT_FOUND로 은닉 (존재 은닉 계약 유지)
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        Long reportedUserId = reportedPet.ownerUserId();

        // 8. 자기 자신 신고 불가
        if (actor.ownerUserId().equals(reportedUserId)) {
            throw new BusinessException(ErrorCode.REPORT_SELF_FORBIDDEN);
        }

        // 9. 동일 reporter·room의 OPEN 신고 조회 (멱등성)
        Optional<itda.report.domain.Report> existing = reportRepository
                .findByReporterUserIdAndRoomIdAndStatus(actor.ownerUserId(), request.roomId(), ReportStatus.OPEN);
        if (existing.isPresent()) {
            return new CreateReportResult(ReportResponse.from(existing.get()), false);
        }

        // 10. 신규 신고 저장 — ReportCreateTransactionService를 경유해 별도 트랜잭션에서 실행
        try {
            return reportCreateTransactionService.saveReport(
                    actor.ownerUserId(),
                    actor.petId(),
                    reportedUserId,
                    reportedPetId,
                    request.roomId(),
                    request.reasonCode(),
                    request.detail()
            );
        } catch (DataIntegrityViolationException e) {
            // 동시 요청 경합으로 OPEN 신고가 생긴 경우에만 멱등 응답으로 복구한다.
            // 다른 FK/CHECK 위반까지 중복 신고로 오인해 숨기지 않는다.
            return reportCreateTransactionService
                    .findExistingOpenReport(actor.ownerUserId(), request.roomId())
                    .orElseThrow(() -> e);
        }
    }
}
