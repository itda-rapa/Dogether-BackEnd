package itda.meetingverification.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomType;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.service.ChatQueryService;
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
import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationRequest;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationStatusResponse;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.meetingverification.repository.MeetingVerificationRequestRepository;
import itda.meetingverification.support.MeetingVerificationFingerprint;
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
 * Meeting 은 권한·카드 상태·DIRECT 전용·양쪽 제출·약속 시간창·양쪽 서버 수신시각 간격·거리·
 * Meeting 생성만 담당한다.
 *
 * <p>권한은 기존 {@link ActivePetQueryService} + MeetingCard 참여자 기준, User/Pet 동시
 * 쓰기는 기존 {@link InteractionPairLockService} 와 기존 lock order(User 오름차순 →
 * Pet 오름차순)를 재사용한다. MeetingCard 의 {@code OPEN -> CANCELED} lifecycle 은
 * 건드리지 않지만, 취소와 GPS 최종 제출은 {@code Pair Lock -> meeting_cards}
 * PESSIMISTIC_WRITE 순서로 직렬화된다.
 *
 * <p>권한·Chat 접근·Pair Lock·authoritative 카드 OPEN 검증을 모두 통과한 뒤에만 immutable
 * request ledger 를 조회하므로, {@code clientRequestId} 만 아는 제3자에게 기존 결과를
 * 노출하지 않는다. 같은 {@code clientRequestId} 재시도는 서버 수신 deadline 과 무관하게
 * replay(또는 conflict)로 수렴하고, 새 {@code clientRequestId} 만 deadline·Location 평가·
 * GPS 판정 대상이다. LOW_ACCURACY 로 {@code CODE_REQUIRED} 가 된 Pet 은 새 GPS 제출로
 * {@code SUBMITTED} 되돌릴 수 없고 이후 확정 경로는 Confirmation Code 흐름(#149)만 사용한다.
 * {@code CODE_REQUIRED} 차단은 deadline 검사보다 먼저 적용되어, 시간창 경과 여부와 무관하게
 * {@code MEETING_VERIFICATION_CODE_REQUIRED} 로 수렴한다.
 */
@Service
public class MeetingVerificationService {

    /** 동일 HMAC key가 유지되는 동안 replay를 보장하는 request 원장의 전역 유일키(PK). */
    private static final String REQUEST_LEDGER_UNIQUE_CONSTRAINT =
            "pk_meeting_verification_requests";

    private final ActivePetQueryService activePetQueryService;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MeetingVerificationRepository meetingVerificationRepository;
    private final MeetingVerificationRequestRepository meetingVerificationRequestRepository;
    private final MeetingRepository meetingRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final ChatQueryService chatQueryService;
    private final LocationService locationService;
    private final MeetingVerificationProperties properties;
    private final MeetingVerificationFingerprint fingerprint;
    private final Clock clock;

    public MeetingVerificationService(ActivePetQueryService activePetQueryService,
                                      MeetingCardRepository meetingCardRepository,
                                      MeetingParticipantRepository meetingParticipantRepository,
                                      ChatRoomRepository chatRoomRepository,
                                      MeetingVerificationRepository meetingVerificationRepository,
                                      MeetingVerificationRequestRepository meetingVerificationRequestRepository,
                                      MeetingRepository meetingRepository,
                                      InteractionPairLockService interactionPairLockService,
                                      ChatQueryService chatQueryService,
                                      LocationService locationService,
                                      MeetingVerificationProperties properties,
                                      Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.meetingCardRepository = meetingCardRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.meetingVerificationRepository = meetingVerificationRepository;
        this.meetingVerificationRequestRepository = meetingVerificationRequestRepository;
        this.meetingRepository = meetingRepository;
        this.interactionPairLockService = interactionPairLockService;
        this.chatQueryService = chatQueryService;
        this.locationService = locationService;
        this.properties = properties;
        this.fingerprint = new MeetingVerificationFingerprint(properties.hmacSecret());
        this.clock = clock;
    }

    /**
     * 위치 제출·판정. 비DIRECT 카드·비참여자·대상 inactive/deleted 는 존재 은닉 404,
     * 비 OPEN 카드 409 로 수렴한다. 권한·Chat 접근·카드 OPEN 검증을 모두 통과한 뒤에만
     * immutable request ledger 를 조회해 replay/conflict 를 처리하고, 새 {@code clientRequestId}
     * 는 {@code CODE_REQUIRED} 차단(deadline 보다 먼저) → 서버 수신 deadline →
     * {@link LocationService#assess} 순서로 검증한다. 따라서 UUID 만 아는 제3자에게 기존 결과를
     * 노출하지 않고, LOW_ACCURACY 로 {@code CODE_REQUIRED} 가 된 Pet 은 시간창 경과 여부와
     * 무관하게 새 GPS 제출로 되돌릴 수 없다.
     */
    @Transactional
    public MeetingVerificationResult submit(Long userId,
                                            long cardId,
                                            MeetingVerificationSubmitCommand command) {
        Instant receivedAt = clock.instant();
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 1. 잠금 없는 카드 identity 조회. 이 snapshot은 pair/card 잠금 뒤 정본으로 재검증한다.
        MeetingCardRepository.MeetingCardIdentity initialCard = meetingCardRepository.findIdentityById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        // 2. 잠금 전에는 카드·기본 참여자·DIRECT 구조만 빠르게 검증한다.
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        requireDirectRoom(initialCard.getRoomId());
        List<Long> participantPetIds =
                meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                ? participantPetIds.get(1)
                : participantPetIds.get(0);

        // 3. Pair 잠금 후 locked actor/target을 authoritative하게 재검증한다.
        InteractionPairContext lockedPair = interactionPairLockService.lockInteractionPair(
                actor.petId(), counterpartPetId);
        requireLockedActor(userId, actor, lockedPair);
        requireActiveTarget(lockedPair);

        // 6. 같은 소유자의 두 Pet 으로 GPS Meeting 을 확정할 수 없다.
        if (Objects.equals(lockedPair.sourcePet().ownerUserId(),
                lockedPair.targetPet().ownerUserId())) {
            throw new BusinessException(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }

        // 4. Block/leftAt 접근성을 Pair Lock 뒤, 카드 행 잠금 전에 재검증한다.
        chatQueryService.requireParticipant(initialCard.getRoomId(), actor.petId());

        // 5. Pair Lock 뒤 authoritative 카드 행 잠금. Block cleanup과 같은 순서다.
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        requireAuthoritativeCard(cardId, card, actor.petId(), counterpartPetId);
        chatQueryService.requireParticipant(card.getRoomId(), actor.petId());

        // 6. OPEN 검증 (status != OPEN 이면 거절)
        if (card.getStatus() != MeetingCardStatus.OPEN) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_OPEN);
        }

        // 7. clientRequestId replay/conflict (immutable request ledger). 이미 접수된 동일
        //    요청은 서버 수신 deadline 과 무관하게 기존 결과로 수렴한다. 권한·Chat·카드 OPEN
        //    검증을 통과한 뒤에만 조회하므로 UUID 만 아는 제3자에게 기존 결과를 노출하지 않는다.
        Optional<MeetingVerificationRequest> byRequestId =
                meetingVerificationRequestRepository.findByClientRequestId(command.clientRequestId());
        if (byRequestId.isPresent()) {
            return replayOrConflict(cardId, actor.petId(), counterpartPetId,
                    byRequestId.get(), command);
        }

        // 8. CODE_REQUIRED 새 GPS 차단(새 clientRequestId 만). LOW_ACCURACY 로 CODE_REQUIRED 가 된
        //    Pet 은 약속 시간창 경과 여부와 무관하게 새 clientRequestId GPS 로
        //    SUBMITTED/CODE_REQUIRED 를 갱신할 수 없다. 이후 확정 경로는 Confirmation Code
        //    흐름(#149)만 사용한다.
        requireNotCodeRequired(cardId, actor.petId());

        // 9. 서버 수신 deadline(새 clientRequestId 만). 잠금 대기 전에 캡처한 receivedAt 기준이므로
        //    lock 대기로 제출 간격 5분이 왜곡되지 않는다. capturedAt 이 과거 시간창 안이고 freshness 가
        //    정상이어도 receivedAt > meetAt + meetingTimeWindow 면 거절한다(EXPIRED terminal).
        requireServerReceiveWithinDeadline(card.getMeetAt(), receivedAt);

        // 10. Location 평가 (권한·상태 검증 이후)
        LocationAssessment assessment = locationService.assess(new LocationInput(
                command.latitude(),
                command.longitude(),
                command.accuracyMeters(),
                command.capturedAt()));

        // 11. meetAt 약속 시간창 검증(경계 포함). 범위 밖이면 ledger·verification·Meeting 을
        //     만들지 않는다.
        requireWithinMeetingTimeWindow(card.getMeetAt(), assessment.location().capturedAt());

        // 12. Location 판정 결과 상태 (LOW_ACCURACY -> CODE_REQUIRED, ACCEPTABLE -> SUBMITTED)
        MeetingVerificationStatus targetStatus = assessment.requiresAccuracyFallback()
                ? MeetingVerificationStatus.CODE_REQUIRED
                : MeetingVerificationStatus.SUBMITTED;

        // 13. immutable ledger INSERT (raw 좌표 없이 HMAC fingerprint 만). 전역 유일키 경합만
        //     409 로 번역한다. invalid/stale 위치는 앞 단계에서 이미 걸러졌다.
        saveRequestLedger(cardId, actor.petId(), command, targetStatus);

        // 14. 기존 Meeting 이 있으면 확정 수렴. GPS 는 확정 응답, CODE 는 409.
        Optional<Meeting> existingMeeting = meetingRepository.findByMeetingCardId(cardId);
        if (existingMeeting.isPresent()) {
            return convergeExistingMeeting(cardId, actor.petId(), counterpartPetId,
                    existingMeeting.get());
        }

        // 15. 최신 verification upsert (SUBMITTED 는 raw 보관, CODE_REQUIRED 는 즉시 scrub)
        MeetingVerification mine = upsertVerification(cardId, actor.petId(), command,
                targetStatus, receivedAt);

        // 16. 양쪽 제출 시 GPS Meeting 판정. counterpart 는 정확히 SUBMITTED 일 때만 유효하다.
        Optional<MeetingVerification> counterpart = meetingVerificationRepository
                .findByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
        if (assessment.requiresAccuracyFallback() || counterpart.isEmpty()) {
            return result(cardId, actor.petId(), mine, counterpart.orElse(null), null);
        }
        if (counterpart.get().getStatus() != MeetingVerificationStatus.SUBMITTED) {
            return result(cardId, actor.petId(), mine, counterpart.get(), null);
        }

        Meeting meeting = confirmGpsMeeting(cardId, card.getMeetAt(), mine, counterpart.get());
        mine.accept();
        counterpart.get().accept();
        meetingVerificationRepository.flush();
        return result(cardId, actor.petId(), mine, counterpart.get(), meeting);
    }

    /** 현재 사용자 기준 만남 확인 상태 조회. 좌표는 반환하지 않는다. */
    @Transactional(readOnly = true)
    public MeetingVerificationStatusResponse getStatus(Long userId, long cardId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        MeetingCard card = meetingCardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        requireDirectRoom(card.getRoomId());
        chatQueryService.requireParticipant(card.getRoomId(), actor.petId());

        // 내 제출·상대 제출·Meeting 을 한 번의 projection query 로 읽는다. 두 SELECT 의 조합으로
        // READ COMMITTED 에서 불가능한 혼합 상태를 만들지 않는다. raw 좌표는 포함하지 않는다.
        MeetingVerificationRepository.MeetingStatusProjection snapshot =
                meetingVerificationRepository.findMeetingStatus(cardId, actor.petId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        return statusResponse(cardId, snapshot);
    }

    /**
     * GPS Meeting 확정: 양쪽 ACCEPTABLE(SUBMITTED) 제출의 약속 시간창·서버 수신시각 간격·거리를
     * Meeting 정책으로 검증한 뒤 {@code meetings} 에 실제 계산 거리와 함께 정확히 한 건 생성한다.
     * raw 좌표는 호출부에서 ACCEPTED 전이와 함께 scrub 한다.
     */
    private Meeting confirmGpsMeeting(long cardId,
                                      Instant meetAt,
                                      MeetingVerification mine,
                                      MeetingVerification counterpart) {
        // 현재 제출·counterpart 제출 모두 약속 시간창(경계 포함)을 만족해야 한다.
        requireWithinMeetingTimeWindow(meetAt, mine.getCapturedAt());
        requireWithinMeetingTimeWindow(meetAt, counterpart.getCapturedAt());

        // 양쪽 제출 간격은 GPS 측위시각(captured_at)이 아니라 서버 수신시각(submitted_at) 기준이다.
        Duration gap = Duration.between(mine.getSubmittedAt(), counterpart.getSubmittedAt()).abs();
        if (gap.compareTo(properties.submissionInterval()) > 0) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }

        double distanceMeters = locationService.distanceMeters(
                locationOf(mine),
                locationOf(counterpart));
        if (distanceMeters > properties.distanceLimitMeters()) {
            throw new BusinessException(ErrorCode.MEETING_DISTANCE_EXCEEDED);
        }

        // uk_meeting_card 는 최후 방어로 남기고, 예상 밖 제약 위반은 재조회로 숨기지 않는다.
        return meetingRepository.saveAndFlush(new Meeting(
                cardId, MeetingVerificationMethod.GPS, clock.instant(), distanceMeters));
    }

    private ValidatedLocation locationOf(MeetingVerification verification) {
        return new ValidatedLocation(
                verification.getLatitude(),
                verification.getLongitude(),
                verification.getAccuracyMeters(),
                verification.getCapturedAt());
    }

    /**
     * 동일 clientRequestId·payload 재시도 수렴. 저장된 request fingerprint 기준으로 Location
     * 재평가·verification replace·Meeting 재생성 없이 replay 한다. GPS Meeting 이 있으면 확정
     * 응답이 우선하고, Meeting 이 없을 때만 최신 actor {@link MeetingVerification} 의 terminal
     * 상태를 확인한다. 현재 verification 이 {@code EXPIRED}/{@code CODE_REQUIRED} 이면 과거
     * ledger 의 최초 상태(SUBMITTED/CODE_REQUIRED)보다 우선해 그 상태로 수렴해 GET status 와
     * 모순되지 않는다. 그 외에는 저장된 request 의 상태(SUBMITTED/CODE_REQUIRED) 기준으로
     * 수렴한다. 이 경로는 서버 수신 deadline 검사보다 먼저 수행되므로 deadline 경과 뒤에도
     * 동일 결과로 수렴한다.
     */
    private MeetingVerificationResult replayOrConflict(long cardId,
                                                       long actorPetId,
                                                       long counterpartPetId,
                                                       MeetingVerificationRequest storedRequest,
                                                       MeetingVerificationSubmitCommand command) {
        String recomputed = fingerprint.compute(cardId, actorPetId,
                command.latitude(), command.longitude(), command.accuracyMeters(),
                command.capturedAt());
        boolean sameRequest = storedRequest.getMeetingCardId().equals(cardId)
                && storedRequest.getParticipantPetId().equals(actorPetId)
                && storedRequest.getFingerprint().equals(recomputed);
        if (!sameRequest) {
            throw new BusinessException(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
        }

        Optional<Meeting> meeting = meetingRepository.findByMeetingCardId(cardId);
        boolean counterpartSubmitted = meetingVerificationRepository
                .existsByMeetingCardIdAndParticipantPetId(cardId, counterpartPetId);
        if (meeting.isPresent()) {
            Meeting existing = meeting.get();
            if (existing.getVerificationMethod() != MeetingVerificationMethod.GPS) {
                throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
            }
            return confirmedResult(cardId, actorPetId, counterpartSubmitted, existing);
        }

        // 동일 clientRequestId replay 는 immutable ledger 의 최초 상태만 보고 잘못된 상태로
        // 되돌리는 것을 막기 위해, Meeting 이 없을 때는 최신 actor MeetingVerification 의
        // terminal 상태가 ledger 최초 상태보다 우선한다. 만료 worker 가 EXPIRED 로 전이한
        // 뒤에는 EXPIRED 로, LOW_ACCURACY 재제출로 CODE_REQUIRED 가 된 뒤에는 CODE_REQUIRED 로
        // 수렴해 GET status 와 모순되지 않는다. raw GPS 복구나 terminal 부활은 없다.
        Optional<MeetingVerification> currentVerification = meetingVerificationRepository
                .findByMeetingCardIdAndParticipantPetId(cardId, actorPetId);
        if (currentVerification.isPresent()) {
            MeetingVerificationStatus currentStatus = currentVerification.get().getStatus();
            if (currentStatus == MeetingVerificationStatus.EXPIRED) {
                return new MeetingVerificationResult(
                        cardId, actorPetId, MeetingVerificationApiStatus.EXPIRED,
                        counterpartSubmitted, null, false, null, null, false, null);
            }
            if (currentStatus == MeetingVerificationStatus.CODE_REQUIRED) {
                return new MeetingVerificationResult(
                        cardId, actorPetId, MeetingVerificationApiStatus.CODE_REQUIRED,
                        counterpartSubmitted, null, false, null, null, true, null);
            }
        }

        boolean codeRequired = storedRequest.getStatus() == MeetingVerificationStatus.CODE_REQUIRED;
        MeetingVerificationApiStatus status = codeRequired
                ? MeetingVerificationApiStatus.CODE_REQUIRED
                : MeetingVerificationApiStatus.WAITING_COUNTERPART;
        return new MeetingVerificationResult(
                cardId, actorPetId, status, counterpartSubmitted, null, false, null, null,
                codeRequired, null);
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
        return confirmedResult(cardId, actorPetId, counterpartSubmitted, meeting);
    }

    /**
     * 새 request 1건을 immutable ledger 에 INSERT 한다. raw 좌표는 넣지 않고 HMAC fingerprint
     * 만 남긴다. ledger 의 전역 유일키 경합만 409 로 번역하고, 그 외
     * {@code DataIntegrityViolationException} 은 절대 뭉뚱그려 변환하지 않는다.
     */
    private void saveRequestLedger(long cardId,
                                   long petId,
                                   MeetingVerificationSubmitCommand command,
                                   MeetingVerificationStatus status) {
        String payloadFingerprint = fingerprint.compute(cardId, petId,
                command.latitude(), command.longitude(), command.accuracyMeters(),
                command.capturedAt());
        MeetingVerificationRequest request = new MeetingVerificationRequest(
                command.clientRequestId(), cardId, petId, payloadFingerprint, status);
        try {
            meetingVerificationRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException exception) {
            if (isRequestLedgerUniqueViolation(exception)) {
                throw new BusinessException(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
            }
            throw exception;
        }
    }

    /**
     * Pet 한 명의 최신 제출 저장. SUBMITTED 는 raw 를 보관하고 CODE_REQUIRED 는 즉시 scrub
     * 한다. {@code submittedAt} 은 서버 수신시각으로 저장한다.
     */
    private MeetingVerification upsertVerification(long cardId,
                                                   long petId,
                                                   MeetingVerificationSubmitCommand command,
                                                   MeetingVerificationStatus status,
                                                   Instant submittedAt) {
        boolean keepRaw = status == MeetingVerificationStatus.SUBMITTED;
        Double latitude = keepRaw ? command.latitude() : null;
        Double longitude = keepRaw ? command.longitude() : null;
        Double accuracyMeters = keepRaw ? command.accuracyMeters() : null;
        Instant capturedAt = keepRaw ? command.capturedAt() : null;

        return meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(cardId, petId)
                .map(existing -> {
                    // EXPIRED 는 GPS 제출 종료 상태다. 새 requestId 로 SUBMITTED/CODE_REQUIRED 로
                    // 부활시키지 않는다(deadline 검사가 1차 방어선이고 이 가드는 2차 방어선).
                    if (existing.getStatus() == MeetingVerificationStatus.EXPIRED) {
                        throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
                    }
                    existing.replace(command.clientRequestId(), latitude, longitude,
                            accuracyMeters, capturedAt, submittedAt, status);
                    return existing;
                })
                .orElseGet(() -> meetingVerificationRepository.saveAndFlush(
                        new MeetingVerification(
                                cardId,
                                petId,
                                command.clientRequestId(),
                                latitude,
                                longitude,
                                accuracyMeters,
                                capturedAt,
                                submittedAt,
                                status)));
    }

    private MeetingVerificationResult result(long cardId,
                                             long petId,
                                             MeetingVerification mine,
                                             MeetingVerification counterpart,
                                             Meeting meeting) {
        MeetingVerificationApiStatus status = apiStatus(
                mine == null ? null : mine.getStatus(),
                meeting == null ? null : meeting.getVerificationMethod());
        boolean confirmed = meeting != null;
        boolean codeRequired = mine != null
                && mine.getStatus() == MeetingVerificationStatus.CODE_REQUIRED;
        return new MeetingVerificationResult(
                cardId,
                petId,
                status,
                counterpart != null,
                meeting == null ? null : meeting.getId(),
                confirmed,
                meeting == null ? null : meeting.getVerificationMethod(),
                meeting == null ? null : meeting.getConfirmedAt(),
                codeRequired,
                meeting == null ? null : meeting.getDistanceMeters());
    }

    private MeetingVerificationResult confirmedResult(long cardId,
                                                      long petId,
                                                      boolean counterpartSubmitted,
                                                      Meeting meeting) {
        return new MeetingVerificationResult(
                cardId,
                petId,
                meeting.getVerificationMethod() == MeetingVerificationMethod.GPS
                        ? MeetingVerificationApiStatus.GPS_CONFIRMED
                        : MeetingVerificationApiStatus.CODE_CONFIRMED,
                counterpartSubmitted,
                meeting.getId(),
                true,
                meeting.getVerificationMethod(),
                meeting.getConfirmedAt(),
                false,
                meeting.getDistanceMeters());
    }

    private MeetingVerificationStatusResponse statusResponse(
            long cardId,
            MeetingVerificationRepository.MeetingStatusProjection snapshot) {
        MeetingVerificationStatus myStatus = statusOf(snapshot.getMyStatus());
        MeetingVerificationMethod method = methodOf(snapshot.getVerificationMethod());
        String counterpartStatusValue = snapshot.getCounterpartStatus();
        Long confirmedAtEpochMillis = snapshot.getConfirmedAtEpochMillis();
        boolean confirmed = snapshot.getMeetingId() != null;
        // Meeting 이 존재하면 양쪽 제출이 함께 확정됐으므로 불변식을 강제한다.
        boolean mySubmitted = confirmed || myStatus != null;
        boolean counterpartSubmitted = confirmed || counterpartStatusValue != null;
        return new MeetingVerificationStatusResponse(
                cardId,
                apiStatus(myStatus, method),
                mySubmitted,
                counterpartSubmitted,
                snapshot.getMeetingId(),
                confirmed,
                method,
                confirmedAtEpochMillis == null
                        ? null
                        : Instant.ofEpochMilli(confirmedAtEpochMillis),
                myStatus == MeetingVerificationStatus.CODE_REQUIRED,
                snapshot.getDistanceMeters());
    }

    /** 영속 상태와 Meeting 확정 방식을 사용자용 API 상태로 매핑한다. */
    private MeetingVerificationApiStatus apiStatus(MeetingVerificationStatus mineStatus,
                                                   MeetingVerificationMethod meetingMethod) {
        if (meetingMethod != null) {
            return meetingMethod == MeetingVerificationMethod.GPS
                    ? MeetingVerificationApiStatus.GPS_CONFIRMED
                    : MeetingVerificationApiStatus.CODE_CONFIRMED;
        }
        if (mineStatus == null) {
            return MeetingVerificationApiStatus.NOT_SUBMITTED;
        }
        return switch (mineStatus) {
            case SUBMITTED -> MeetingVerificationApiStatus.WAITING_COUNTERPART;
            case CODE_REQUIRED -> MeetingVerificationApiStatus.CODE_REQUIRED;
            case REJECTED -> MeetingVerificationApiStatus.REJECTED;
            case EXPIRED -> MeetingVerificationApiStatus.EXPIRED;
            case ACCEPTED -> MeetingVerificationApiStatus.WAITING_COUNTERPART; // unreachable
        };
    }

    private static MeetingVerificationStatus statusOf(String value) {
        return value == null ? null : MeetingVerificationStatus.valueOf(value);
    }

    private static MeetingVerificationMethod methodOf(String value) {
        return value == null ? null : MeetingVerificationMethod.valueOf(value);
    }

    private void requireDirectRoom(Long roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        if (room.getType() != RoomType.DIRECT) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
    }

    private void requireAuthoritativeCard(long cardId, MeetingCard card, long actorPetId,
                                         long counterpartPetId) {
        if (card.getRoomId() == null || card.getStatus() == null) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        requireDirectRoom(card.getRoomId());
        List<Long> participantPetIds = meetingParticipantRepository
                .findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2
                || !participantPetIds.contains(actorPetId)
                || !participantPetIds.contains(counterpartPetId)) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        ChatRoom room = chatRoomRepository.findById(card.getRoomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        boolean sameDirectPair = room.getType() == RoomType.DIRECT
                && ((Objects.equals(room.getPetLowId(), actorPetId)
                && Objects.equals(room.getPetHighId(), counterpartPetId))
                || (Objects.equals(room.getPetLowId(), counterpartPetId)
                && Objects.equals(room.getPetHighId(), actorPetId)));
        if (!sameDirectPair) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
    }

    /** Hibernate/Spring 예외 cause chain 에서 특정 constraint 위반인지 판별한다. */
    private boolean isRequestLedgerUniqueViolation(DataIntegrityViolationException exception) {
        return isConstraintViolation(exception, REQUEST_LEDGER_UNIQUE_CONSTRAINT);
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

    /** 약속 시간창 검증. 경계값(meetAt ± window)은 포함한다. */
    private void requireWithinMeetingTimeWindow(Instant meetAt, Instant capturedAt) {
        Duration window = properties.meetingTimeWindow();
        if (capturedAt.isBefore(meetAt.minus(window)) || capturedAt.isAfter(meetAt.plus(window))) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }
    }

    /**
     * 서버 수신 deadline. {@code receivedAt > meetAt + meetingTimeWindow} 이면
     * {@code MEETING_TIME_WINDOW_EXCEEDED} 로 거절한다. 경계값(정확히 meetAt + window)은 포함한다.
     */
    private void requireServerReceiveWithinDeadline(Instant meetAt, Instant receivedAt) {
        if (receivedAt.isAfter(meetAt.plus(properties.meetingTimeWindow()))) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }
    }

    /**
     * CODE_REQUIRED GPS 재제출 차단. LOW_ACCURACY 로 {@code CODE_REQUIRED} 가 된 Pet 은 새
     * {@code clientRequestId} GPS 로 {@code SUBMITTED}/{@code CODE_REQUIRED} 를 갱신할 수 없다.
     * 이후 확정 경로는 Confirmation Code 흐름(#149)만 사용한다. 조회만 수행하므로 기존
     * {@code CODE_REQUIRED} verification 과 raw GPS null 은 그대로 유지된다.
     */
    private void requireNotCodeRequired(long cardId, long petId) {
        meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(cardId, petId)
                .filter(verification -> verification.getStatus()
                        == MeetingVerificationStatus.CODE_REQUIRED)
                .ifPresent(verification -> {
                    throw new BusinessException(ErrorCode.MEETING_VERIFICATION_CODE_REQUIRED);
                });
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

    /** 대상(상대) User/Pet 이 ACTIVE 가 아니면 존재 은닉 404 로 수렴한다. */
    private void requireActiveTarget(InteractionPairContext lockedPair) {
        if (lockedPair.targetUser().accountStatus() != AccountStatus.ACTIVE
                || lockedPair.targetPet().status() != PetStatus.ACTIVE
                || lockedPair.targetPet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
    }
}
