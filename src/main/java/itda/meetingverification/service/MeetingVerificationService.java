package itda.meetingverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.location.dto.LocationAssessment;
import itda.location.dto.LocationInput;
import itda.location.dto.ValidatedLocation;
import itda.location.service.LocationService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingverification.MeetingVerificationProperties;
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.domain.MeetingVerification;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만남 위치 제출·양쪽 GPS 확인(#111).
 *
 * <p>Location 은 좌표 형식·freshness·accuracy 품질만 판정하고({@link LocationService}),
 * Meeting 은 권한·카드 상태·양쪽 제출·시간 간격·거리·Meeting 생성만 담당한다.
 *
 * <p>권한은 기존 {@link ActivePetQueryService} + MeetingCard 참여자 기준, User/Pet 동시
 * 쓰기는 기존 {@link InteractionPairLockService} 와 기존 lock order(User 오름차순 →
 * Pet 오름차순)를 재사용한다. MeetingCard 의 {@code OPEN -> CANCELED} lifecycle 은
 * 건드리지 않는다.
 */
@Service
public class MeetingVerificationService {

    private static final String CLIENT_REQUEST_UNIQUE_CONSTRAINT =
            "uk_meeting_verification_client_request";
    private static final String MEETING_CARD_UNIQUE_CONSTRAINT = "uk_meeting_card";

    private final ActivePetQueryService activePetQueryService;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingVerificationRepository meetingVerificationRepository;
    private final MeetingRepository meetingRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final LocationService locationService;
    private final MeetingVerificationProperties properties;
    private final Clock clock;

    public MeetingVerificationService(ActivePetQueryService activePetQueryService,
                                      MeetingCardRepository meetingCardRepository,
                                      MeetingParticipantRepository meetingParticipantRepository,
                                      MeetingVerificationRepository meetingVerificationRepository,
                                      MeetingRepository meetingRepository,
                                      InteractionPairLockService interactionPairLockService,
                                      LocationService locationService,
                                      MeetingVerificationProperties properties,
                                      Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.meetingCardRepository = meetingCardRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.meetingVerificationRepository = meetingVerificationRepository;
        this.meetingRepository = meetingRepository;
        this.interactionPairLockService = interactionPairLockService;
        this.locationService = locationService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 위치 제출·판정. 카드 부재 404, 참여자 아님 403, 비 OPEN 카드 409 로 수렴한다.
     *
     * <p>권한·카드 상태 검증을 모두 통과한 뒤에만 {@link LocationService#assess} 를
     * 호출하므로, 권한 없는 사용자는 {@code LOCATION_INVALID}/{@code LOCATION_STALE}
     * 응답으로 상태를 추론할 수 없다.
     */
    @Transactional
    public MeetingVerificationResult submit(Long userId,
                                            long cardId,
                                            MeetingVerificationSubmitCommand command) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 1. 카드 존재·참여자 확인 (Location 평가보다 먼저)
        if (!meetingCardRepository.existsById(cardId)) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_NOT_PARTICIPANT);
        }

        // M1 DIRECT: 카드 참여자는 정확히 두 Pet.
        List<Long> participantPetIds =
                meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                ? participantPetIds.get(1)
                : participantPetIds.get(0);

        // 2. User -> Pet 순 오름차순 pair 잠금 + locked snapshot 재검증
        InteractionPairContext lockedPair = interactionPairLockService.lockInteractionPair(
                actor.petId(), counterpartPetId);
        requireLockedActor(userId, actor, lockedPair);

        // 3. 카드 authoritative 재조회 + OPEN 검증 (status != OPEN 이면 거절)
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        if (card.getStatus() != MeetingCardStatus.OPEN) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_OPEN);
        }

        // 4. clientRequestId 전역 멱등/충돌 (기존 Meeting 조회보다 먼저. 이미 확정된 카드에서도
        //    같은 clientRequestId 의 card/pet/payload 충돌을 숨기지 않는다)
        Optional<MeetingVerification> byRequestId =
                meetingVerificationRepository.findByClientRequestId(command.clientRequestId());
        if (byRequestId.isPresent()) {
            MeetingVerification existing = byRequestId.get();
            if (!existing.getMeetingCardId().equals(cardId)
                    || !existing.getParticipantPetId().equals(actor.petId())
                    || !samePayload(existing, command)) {
                throw new BusinessException(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
            }
            // 동일 요청 재시도: Location 평가를 재호출하지 않고 현재 상태로 수렴한다.
            return convergeRetry(cardId, actor.petId(), counterpartPetId);
        }

        // 5. 기존 Meeting 확인 (fresh clientRequestId. GPS 확정이면 수렴, 그 외 방식이면 409)
        Optional<Meeting> existingMeeting = meetingRepository.findByMeetingCardId(cardId);
        if (existingMeeting.isPresent()) {
            return convergeExistingMeeting(cardId, actor.petId(), counterpartPetId,
                    existingMeeting.get());
        }

        // 6. Location 평가 (권한·상태 검증 이후)
        LocationAssessment assessment = locationService.assess(new LocationInput(
                command.latitude(),
                command.longitude(),
                command.accuracyMeters(),
                command.capturedAt()));

        // 7. verification 저장·갱신 (LOW_ACCURACY -> CODE_REQUIRED, ACCEPTABLE -> SUBMITTED)
        MeetingVerificationStatus targetStatus = assessment.requiresAccuracyFallback()
                ? MeetingVerificationStatus.CODE_REQUIRED
                : MeetingVerificationStatus.SUBMITTED;
        upsertVerification(cardId, actor.petId(), command, targetStatus);

        // 8. 양쪽 제출 시 GPS Meeting 판정
        boolean counterpartSubmitted = meetingVerificationRepository
                .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
        if (assessment.requiresAccuracyFallback() || !counterpartSubmitted) {
            return new MeetingVerificationResult(
                    cardId, actor.petId(), counterpartSubmitted, null, false, null, null);
        }

        MeetingVerification counterpart = meetingVerificationRepository
                .findByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        if (counterpart.getStatus() == MeetingVerificationStatus.CODE_REQUIRED) {
            // 한쪽이라도 CODE_REQUIRED 면 GPS Meeting 을 만들지 않는다.
            return new MeetingVerificationResult(
                    cardId, actor.petId(), true, null, false, null, null);
        }

        Meeting meeting = confirmGpsMeeting(cardId, assessment.location(), counterpart);
        return new MeetingVerificationResult(
                cardId, actor.petId(), true, meeting.getId(), true,
                meeting.getVerificationMethod(), meeting.getConfirmedAt());
    }

    /** 현재 사용자 기준 만남 확인 상태 조회. 좌표는 반환하지 않는다. */
    @Transactional(readOnly = true)
    public MeetingVerificationStatusResponse getStatus(Long userId, long cardId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        if (!meetingCardRepository.existsById(cardId)) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_NOT_PARTICIPANT);
        }

        boolean counterpartSubmitted = false;
        List<Long> participantPetIds =
                meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() == 2) {
            Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                    ? participantPetIds.get(1)
                    : participantPetIds.get(0);
            counterpartSubmitted = meetingVerificationRepository
                    .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
        }

        Optional<MeetingVerification> mine = meetingVerificationRepository
                .findByMeetingCardIdAndParticipantPetId(cardId, actor.petId());
        Optional<Meeting> meeting = meetingRepository.findByMeetingCardId(cardId);

        return new MeetingVerificationStatusResponse(
                cardId,
                mine.isPresent(),
                counterpartSubmitted,
                meeting.map(Meeting::getId).orElse(null),
                meeting.isPresent(),
                meeting.map(Meeting::getVerificationMethod).orElse(null),
                meeting.map(Meeting::getConfirmedAt).orElse(null),
                mine.isPresent()
                        && mine.get().getStatus() == MeetingVerificationStatus.CODE_REQUIRED);
    }

    /**
     * GPS Meeting 확정: 양쪽 ACCEPTABLE 제출의 시각 간격·거리를 Meeting 정책으로 검증한 뒤
     * {@code meetings} 에 정확히 한 건 생성한다.
     */
    private Meeting confirmGpsMeeting(long cardId,
                                      ValidatedLocation mine,
                                      MeetingVerification counterpart) {
        ValidatedLocation counterpartLocation = new ValidatedLocation(
                counterpart.getLatitude(),
                counterpart.getLongitude(),
                counterpart.getAccuracyMeters(),
                counterpart.getCapturedAt());

        Duration gap = Duration.between(mine.capturedAt(), counterpartLocation.capturedAt()).abs();
        if (gap.compareTo(properties.submissionInterval()) > 0) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }

        double distanceMeters = locationService.distanceMeters(mine, counterpartLocation);
        if (distanceMeters > properties.distanceLimitMeters()) {
            throw new BusinessException(ErrorCode.MEETING_DISTANCE_EXCEEDED);
        }

        return saveGpsMeeting(cardId);
    }

    /**
     * GPS Meeting 저장. {@code uk_meeting_card} 경합은 기존 GPS Meeting 을 재조회해 멱등
     * 응답으로 수렴하고, 그 외 제약 위반은 숨기지 않고 그대로 전파한다.
     */
    private Meeting saveGpsMeeting(long cardId) {
        try {
            return meetingRepository.saveAndFlush(new Meeting(
                    cardId, MeetingVerificationMethod.GPS, clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            if (!isMeetingCardUniqueViolation(exception)) {
                throw exception;
            }
            Meeting existing = meetingRepository.findByMeetingCardId(cardId)
                    .orElseThrow(() -> exception);
            if (existing.getVerificationMethod() != MeetingVerificationMethod.GPS) {
                throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
            }
            return existing;
        }
    }

    /**
     * 동일 clientRequestId·payload 재시도 수렴. GPS Meeting 이 있으면 confirmed 응답,
     * 없으면 미확정 idempotent 응답을 반환한다. Location 평가는 재호출하지 않는다.
     */
    private MeetingVerificationResult convergeRetry(long cardId,
                                                    long actorPetId,
                                                    long counterpartPetId) {
        return meetingRepository.findByMeetingCardId(cardId)
                .map(meeting -> convergeExistingMeeting(cardId, actorPetId, counterpartPetId, meeting))
                .orElseGet(() -> {
                    boolean counterpartSubmitted = meetingVerificationRepository
                            .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
                    return new MeetingVerificationResult(
                            cardId, actorPetId, counterpartSubmitted, null, false, null, null);
                });
    }

    private MeetingVerificationResult convergeExistingMeeting(long cardId,
                                                              long actorPetId,
                                                              long counterpartPetId,
                                                              Meeting meeting) {
        if (meeting.getVerificationMethod() != MeetingVerificationMethod.GPS) {
            throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
        }
        boolean counterpartSubmitted = meetingVerificationRepository
                .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
        return new MeetingVerificationResult(
                cardId, actorPetId, counterpartSubmitted, meeting.getId(), true,
                meeting.getVerificationMethod(), meeting.getConfirmedAt());
    }

    /**
     * Pet 한 명의 제출 저장. 같은 {@code clientRequestId} 멱등·충돌 검사는 호출부에서
     * 수행하므로, 여기서는 {@code (card, pet)} 슬롯에 최신 제출을 upsert 한다.
     * INSERT 시 전역 {@code uk_meeting_verification_client_request} 경합만 409 로 번역한다.
     */
    private void upsertVerification(long cardId,
                                    long petId,
                                    MeetingVerificationSubmitCommand command,
                                    MeetingVerificationStatus status) {
        try {
            meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(cardId, petId)
                    .ifPresentOrElse(existing -> {
                                existing.replace(
                                        command.clientRequestId(),
                                        command.latitude(),
                                        command.longitude(),
                                        command.accuracyMeters(),
                                        command.capturedAt(),
                                        status);
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
                                            command.capturedAt(),
                                            status)));
        } catch (DataIntegrityViolationException exception) {
            if (isClientRequestUniqueViolation(exception)) {
                throw new BusinessException(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
            }
            // uk_meeting_verification_pet 등 다른 제약 위반은 기존 오류 처리 정책을 유지한다.
            throw exception;
        }
    }

    /** Hibernate/Spring 예외 cause chain 에서 특정 constraint 위반인지 판별한다. */
    private boolean isClientRequestUniqueViolation(DataIntegrityViolationException exception) {
        return isConstraintViolation(exception, CLIENT_REQUEST_UNIQUE_CONSTRAINT);
    }

    private boolean isMeetingCardUniqueViolation(DataIntegrityViolationException exception) {
        return isConstraintViolation(exception, MEETING_CARD_UNIQUE_CONSTRAINT);
    }

    private boolean isConstraintViolation(DataIntegrityViolationException exception,
                                          String constraintName) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException constraintViolation) {
                return constraintName.equalsIgnoreCase(constraintViolation.getConstraintName());
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
