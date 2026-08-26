package itda.meetingverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingverification.domain.MeetingVerification;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만남 위치 제출·양쪽 확인 기반(#111). Location 비의존 범위만 구현한다.
 *
 * <p>권한은 기존 {@link ActivePetQueryService} + MeetingCard 참여자 기준, User/Pet 동시
 * 쓰기는 기존 {@link InteractionPairLockService} 와 기존 lock order(User 오름차순 →
 * Pet 오름차순)를 재사용한다. MeetingCard 의 {@code OPEN -> CANCELED} lifecycle 은
 * 건드리지 않는다.
 *
 * <p>제출·대기 상태는 {@code meeting_verifications} 행의 존재와 {@code counterpartSubmitted}
 * 로만 표현한다. {@code meetings} 행은 GPS 또는 Code 방식으로 실제 확정이 일어나는
 * 시점(Location 판정 또는 Confirmation Code, #146 병합 뒤)에만 생성된다.
 *
 * <p>SEAM(#146): 양쪽 제출이 모인 뒤의 좌표·accuracy·stale 검증과 거리·시간 판정, 그리고
 * GPS/CODE 확정은 Location 계약이 dev 에 병합된 뒤 이 서비스에서 LocationService 결과를
 * 받아 수행한다. Confirmation Code 발급·입력·검증, Review/Footprint 는 이후 태스크다.
 */
@Service
public class MeetingVerificationService {

    /** 전역 clientRequestId 멱등키 제약(01_M3_통합_ERD.md §6). 동시 경합 시 409 로 번역한다. */
    private static final String CLIENT_REQUEST_UNIQUE_CONSTRAINT =
            "uk_meeting_verification_client_request";

    private final ActivePetQueryService activePetQueryService;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingVerificationRepository meetingVerificationRepository;
    private final InteractionPairLockService interactionPairLockService;

    public MeetingVerificationService(ActivePetQueryService activePetQueryService,
                                      MeetingCardRepository meetingCardRepository,
                                      MeetingParticipantRepository meetingParticipantRepository,
                                      MeetingVerificationRepository meetingVerificationRepository,
                                      InteractionPairLockService interactionPairLockService) {
        this.activePetQueryService = activePetQueryService;
        this.meetingCardRepository = meetingCardRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.meetingVerificationRepository = meetingVerificationRepository;
        this.interactionPairLockService = interactionPairLockService;
    }

    /**
     * 위치 제출 저장. 카드 부재는 404, 참여자 아님은 403, 취소된 카드는 409 로 수렴한다(M3 계약).
     *
     * <p>동일 {@code clientRequestId} 는 카드·Pet·payload 가 모두 같을 때만 멱등으로
     * 기존 제출을 반환하고, 하나라도 다르면
     * {@link ErrorCode#MEETING_VERIFICATION_REQUEST_CONFLICT}(409) 로 거부한다. 같은 Pet 의
     * 새 {@code clientRequestId} 재제출은 기존 행을 대체한다(최신 제출 우선).
     */
    @Transactional
    public MeetingVerificationResult submit(Long userId,
                                            long cardId,
                                            MeetingVerificationSubmitCommand command) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        if (!meetingCardRepository.existsById(cardId)) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_NOT_PARTICIPANT);
        }

        // M1 DIRECT: 카드 참여자는 정확히 두 Pet. 상대를 찾아 pair 잠금을 잡는다.
        List<Long> participantPetIds =
                meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                ? participantPetIds.get(1)
                : participantPetIds.get(0);

        // User -> Pet 순 오름차순 잠금. MeetingCardService.confirm 과 같은 계약을 쓴다.
        InteractionPairContext lockedPair = interactionPairLockService.lockInteractionPair(
                actor.petId(), counterpartPetId);
        requireLockedActor(userId, actor, lockedPair);

        // 잠금 뒤 카드를 다시 읽는다. 잠금 전에 findById 로 읽으면 영속성 컨텍스트에
        // 캐시된 상태가 남아 findByIdForUpdate 가 stale 상태를 돌려주는 함정을 피한다
        // (MeetingCardService.cancel 주석과 같은 이유).
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        if (card.getStatus() == MeetingCardStatus.CANCELED) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_OPEN);
        }

        upsertVerification(cardId, actor.petId(), command);

        boolean counterpartSubmitted = meetingVerificationRepository
                .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);

        // SEAM(#146): 양쪽 제출이 모이면 LocationService(미병합) 판정으로
        // ACCEPTED/CODE_REQUIRED/REJECTED 판정과 Meeting 확정(verification_method·confirmed_at)
        // 을 수행한다. 현재는 제출 저장까지만 하고 meetings 행을 만들지 않는다.

        return new MeetingVerificationResult(cardId, actor.petId(), counterpartSubmitted);
    }

    /**
     * Pet 한 명의 제출 저장.
     *
     * <ul>
     *   <li>같은 {@code clientRequestId} + 같은 카드 + 같은 Pet + 동일 payload → 기존 제출 반환(멱등)</li>
     *   <li>같은 {@code clientRequestId} + card/pet/payload 중 하나라도 다름 → 409</li>
     *   <li>같은 Pet 의 새 {@code clientRequestId} → 기존 행을 최신 값으로 대체</li>
     * </ul>
     *
     * <p>사전 조회와 쓰기 사이에 다른 카드·다른 Pet 요청이 같은 {@code clientRequestId} 를
     * 동시에 커밋하면 전역 {@code uk_meeting_verification_client_request} 에서 경합한다. 쓰기를
     * 즉시 flush 해 이 위반을 여기서 드러내고 {@link ErrorCode#MEETING_VERIFICATION_REQUEST_CONFLICT}
     * 로 번역한다. 다른 제약(예: {@code uk_meeting_verification_pet})은 번역하지 않고 그대로
     * 흘려보낸다.
     */
    private void upsertVerification(long cardId,
                                    long petId,
                                    MeetingVerificationSubmitCommand command) {
        // 전역 멱등키(uk_meeting_verification_client_request).
        var byRequestId =
                meetingVerificationRepository.findByClientRequestId(command.clientRequestId());
        if (byRequestId.isPresent()) {
            MeetingVerification existing = byRequestId.get();
            if (existing.getMeetingCardId().equals(cardId)
                    && existing.getParticipantPetId().equals(petId)
                    && samePayload(existing, command)) {
                // 같은 clientRequestId + 같은 카드/Pet + 동일 payload: 멱등, 기존 제출 유지.
                return;
            }
            // card / pet / payload 중 하나라도 다르면 요청 충돌.
            throw new BusinessException(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
        }

        try {
            meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(cardId, petId)
                    .ifPresentOrElse(existing -> {
                                existing.replace(
                                        command.clientRequestId(),
                                        command.latitude(),
                                        command.longitude(),
                                        command.accuracyMeters(),
                                        command.capturedAt());
                                // UPDATE 를 즉시 flush 해 전역 UNIQUE 경합을 이 지점에서 드러낸다.
                                meetingVerificationRepository.flush();
                            },
                            () -> meetingVerificationRepository.saveAndFlush(
                                    new MeetingVerification(
                                            cardId,
                                            petId,
                                            command.clientRequestId(),
                                            command.latitude(),
                                            command.longitude(),
                                            command.accuracyMeters(),
                                            command.capturedAt())));
        } catch (DataIntegrityViolationException exception) {
            if (isClientRequestUniqueViolation(exception)) {
                throw new BusinessException(
                        ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
            }
            // uk_meeting_verification_pet 등 다른 제약 위반은 기존 오류 처리 정책을 유지한다.
            throw exception;
        }
    }

    /**
     * Hibernate/Spring 예외 cause chain 에서 constraint name 을 찾아
     * {@code uk_meeting_verification_client_request} 위반인지 판별한다.
     * FriendRequestCommandService/ChatMessageService 와 같은 프로젝트 패턴이다.
     */
    private boolean isClientRequestUniqueViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException constraintViolation) {
                return CLIENT_REQUEST_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                        constraintViolation.getConstraintName());
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean samePayload(MeetingVerification verification,
                                MeetingVerificationSubmitCommand command) {
        return verification.getLatitude() == command.latitude()
                && verification.getLongitude() == command.longitude()
                && verification.getAccuracyMeters() == command.accuracyMeters()
                && sameCapturedAt(verification.getCapturedAt(), command.capturedAt());
    }

    /**
     * PostgreSQL TIMESTAMPTZ 는 마이크로초까지 저장하고 그보다 작은 단위는 반올림하므로,
     * 저장값과 요청값은 마이크로초 단위로 비교한다(±1µs 이내면 같은 payload).
     */
    private boolean sameCapturedAt(Instant stored, Instant submitted) {
        return Math.abs(toMicros(stored) - toMicros(submitted)) <= 1;
    }

    private long toMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    /** MeetingCardService.requireLockedActor 와 같은 검증. 잠긴 pair 가 요청자와 일치하는지 본다. */
    private void requireLockedActor(Long userId,
                                    ActivePetContext actor,
                                    InteractionPairContext lockedPair) {
        if (!Objects.equals(lockedPair.sourceUser().userId(), userId)
                || !Objects.equals(actor.ownerUserId(), userId)
                || !Objects.equals(lockedPair.sourcePet().ownerUserId(), userId)
                || !Objects.equals(lockedPair.sourceUser().activePetId(), actor.petId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (lockedPair.sourceUser().accountStatus() != AccountStatus.ACTIVE
                || lockedPair.sourcePet().status() != PetStatus.ACTIVE
                || lockedPair.sourcePet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }
}
