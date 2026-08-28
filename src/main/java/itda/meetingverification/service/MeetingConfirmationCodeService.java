package itda.meetingverification.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomType;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.service.ChatQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingverification.MeetingVerificationProperties;
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.domain.MeetingConfirmationCode;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.ConfirmationCodeCreateResult;
import itda.meetingverification.dto.ConfirmationCodeResult;
import itda.meetingverification.repository.MeetingConfirmationCodeRepository;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOW_ACCURACY가 저장된 카드에서만 작동하는 두 단계 Confirmation Code fallback(#149).
 *
 * <p>접근 제어는 GPS 제출({@link MeetingVerificationService})과 동일한 순서를 재사용한다:
 * Active Pet → 잠금 없는 카드 identity → 참여자·DIRECT·Chat 접근 → Pair Lock → locked actor/target
 * 재검증 → 같은 소유자 차단 → Chat 접근 은닉 → Card FOR UPDATE → authoritative 재검증 →
 * Chat 재검증 → OPEN. 비참여자·비DIRECT·대상 inactive/deleted 는 모두 존재 은닉 404 로 수렴해
 * GPS 와 Code 의 오류 의미가 갈라지지 않는다.
 *
 * <p>Code 는 약속 시간창({@code meetAt + meetingTimeWindow})을 넘겨 확정할 수 없다.
 * {@code receivedAt} 은 각 public 메서드의 첫 실행문에서 캡처해 lock 대기 시간이 시간창 정책을
 * 왜곡하지 않게 하고, 아직 Meeting 이 없는 새 Code 흐름에만 서버 수신 deadline 을 적용한다.
 * 이미 확정된 GPS/CODE Meeting 은 deadline 과 무관하게 기존 확정 응답으로 수렴한다.
 *
 * <p>CODE Meeting 확정은 같은 transaction 에서 카드의 미확정 SUBMITTED·CODE_REQUIRED
 * verification 을 ACCEPTED 로 전이하고 raw GPS 를 scrub 하므로, 확정 뒤 raw 좌표가 영구
 * 잔존하지 않는다. 정상 동시성 제어는 기존 Pair → Card → Code lock 순서가 정본이고
 * {@code uk_meeting_card} 위반 뒤 재조회 수렴을 정합성 수단으로 쓰지 않는다.
 *
 * <p>발급 응답 {@code expiresAt} 은 {@code min(now + TTL, meetAt + meetingTimeWindow)}
 * 이므로, 반환 이후에는 TTL/deadline 어느 쪽이든 실제로 사용 불가하다. verifier 가 이미
 * 검증한 코드도 아직 유효하면 재발급을 409 로 거절한다. 만료 또는 무효화된 verifier 완료
 * 코드는 deadline 안에서만 새 cycle 로 재발급하며, 상대의 재검증이 필요하다.
 */
@Service
public class MeetingConfirmationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ActivePetQueryService activePetQueryService;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository participantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MeetingVerificationRepository verificationRepository;
    private final MeetingConfirmationCodeRepository codeRepository;
    private final MeetingRepository meetingRepository;
    private final InteractionPairLockService pairLockService;
    private final ChatQueryService chatQueryService;
    private final PasswordEncoder passwordEncoder;
    private final ConfirmationCodeProperties properties;
    private final MeetingVerificationProperties meetingVerificationProperties;
    private final Clock clock;

    public MeetingConfirmationCodeService(ActivePetQueryService activePetQueryService,
                                          MeetingCardRepository meetingCardRepository,
                                          MeetingParticipantRepository participantRepository,
                                          ChatRoomRepository chatRoomRepository,
                                          MeetingVerificationRepository verificationRepository,
                                          MeetingConfirmationCodeRepository codeRepository,
                                          MeetingRepository meetingRepository,
                                          InteractionPairLockService pairLockService,
                                          ChatQueryService chatQueryService,
                                          PasswordEncoder passwordEncoder,
                                          ConfirmationCodeProperties properties,
                                          MeetingVerificationProperties meetingVerificationProperties,
                                          Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.meetingCardRepository = meetingCardRepository;
        this.participantRepository = participantRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.verificationRepository = verificationRepository;
        this.codeRepository = codeRepository;
        this.meetingRepository = meetingRepository;
        this.pairLockService = pairLockService;
        this.chatQueryService = chatQueryService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.meetingVerificationProperties = meetingVerificationProperties;
        this.clock = clock;
    }

    @Transactional
    public ConfirmationCodeCreateResult issue(Long userId, long cardId) {
        Instant receivedAt = clock.instant();
        Access access = lockCodeEligibleCard(userId, cardId);
        Meeting meeting = meetingRepository.findByMeetingCardId(cardId).orElse(null);
        if (meeting != null) {
            throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
        }
        requireCodeRequired(cardId);
        MeetingConfirmationCode existing = codeRepository.findByMeetingCardIdForUpdate(cardId)
                .orElse(null);
        Instant stateCheckedAt = clock.instant();
        if (existing != null && !existing.getIssuerPetId().equals(access.actor().petId())) {
            throw new BusinessException(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN);
        }
        // verifier가 검증 완료했고 아직 유효한 코드만 재발급을 거절한다.
        // verifier 확인 뒤 TTL이 만료/무효화된 코드는 새 cycle로 재발급을 허용한다.
        if (existing != null
                && existing.getVerifierConfirmedAt() != null
                && existing.isUsableAt(stateCheckedAt)) {
            throw new BusinessException(ErrorCode.MEETING_CODE_REISSUE_FORBIDDEN);
        }
        requireServerReceiveBeforeDeadline(access.card().getMeetAt(), receivedAt);

        String plaintext = String.format("%04d", RANDOM.nextInt(10_000));
        String hash = passwordEncoder.encode(plaintext);
        // bcrypt 계산 후 mutation 직전 시각으로 TTL을 계산해, deadline 직전의
        // hash 생성 지연으로 이미 만료된 expiresAt을 저장·응답하지 않는다.
        Instant issuedAt = clock.instant();
        Instant meetingDeadline = access.card().getMeetAt()
                .plus(meetingVerificationProperties.meetingTimeWindow());
        Instant expiresAt = issuedAt.plus(properties.ttl());
        if (meetingDeadline.isBefore(expiresAt)) {
            expiresAt = meetingDeadline;
        }
        // lock 대기로 now가 deadline에 닿았거나 넘었으면 이미 만료된 코드를
        // 201로 발급하지 않는다. expiresAt은 임의 보정 없이 issuedAt보다 엄격히 미래여야 한다.
        if (!expiresAt.isAfter(issuedAt)) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }

        if (existing != null) {
            existing.reissue(hash, expiresAt);
        } else {
            codeRepository.save(new MeetingConfirmationCode(
                    cardId, access.actor().petId(), hash, expiresAt));
        }
        return new ConfirmationCodeCreateResult(plaintext, expiresAt);
    }

    @Transactional(noRollbackFor = ConfirmationCodeStatePersistException.class)
    public ConfirmationCodeResult verify(Long userId, long cardId, String suppliedCode) {
        Instant receivedAt = clock.instant();
        Access access = lockCodeEligibleCard(userId, cardId);
        Meeting meeting = meetingRepository.findByMeetingCardId(cardId).orElse(null);
        if (meeting != null) {
            return alreadyConfirmedCodeMeeting(meeting);
        }
        requireCodeRequired(cardId);
        MeetingConfirmationCode code = codeRepository.findByMeetingCardIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CODE_NOT_AVAILABLE));
        requireServerReceiveBeforeDeadline(access.card().getMeetAt(), receivedAt);
        Instant now = clock.instant();
        code.requireUsable(now);
        if (code.getIssuerPetId().equals(access.actor().petId())) {
            throw new BusinessException(ErrorCode.MEETING_CODE_ISSUER_FORBIDDEN);
        }
        if (!passwordEncoder.matches(suppliedCode, code.getCodeHash())) {
            code.recordFailedAttempt(now, properties.maxAttempts());
        }
        code.verifyBy(access.actor().petId(), now);
        return new ConfirmationCodeResult(cardId, "WAITING_ISSUER_CONFIRMATION", null,
                MeetingVerificationMethod.CODE, null);
    }

    @Transactional(noRollbackFor = ConfirmationCodeStatePersistException.class)
    public ConfirmationCodeResult confirm(Long userId, long cardId) {
        Instant receivedAt = clock.instant();
        Access access = lockCodeEligibleCard(userId, cardId);
        Meeting meeting = meetingRepository.findByMeetingCardId(cardId).orElse(null);
        if (meeting != null) {
            return alreadyConfirmedCodeMeeting(meeting);
        }
        requireCodeRequired(cardId);
        MeetingConfirmationCode code = codeRepository.findByMeetingCardIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CODE_NOT_AVAILABLE));
        requireServerReceiveBeforeDeadline(access.card().getMeetAt(), receivedAt);
        Instant now = clock.instant();
        code.requireUsable(now);
        if (!code.getIssuerPetId().equals(access.actor().petId())) {
            throw new BusinessException(ErrorCode.MEETING_CODE_ISSUER_FORBIDDEN);
        }
        code.confirmByIssuer(now);
        Meeting created = meetingRepository.saveAndFlush(
                new Meeting(cardId, MeetingVerificationMethod.CODE, now, null));
        // Meeting INSERT 와 같은 transaction 에서 미확정 verification 을 ACCEPTED 로 종결하고
        // raw GPS 를 scrub 한다. 실패하면 Meeting·code confirmation 까지 함께 rollback 된다.
        verificationRepository.acceptUnconfirmedByCard(cardId);
        return confirmed(created);
    }

    /** GPS 제출과 동일한 접근 은닉·Pair Lock → Card FOR UPDATE 순서. receivedAt 은 호출부에서 캡처한다. */
    private Access lockCodeEligibleCard(Long userId, long cardId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 잠금 없는 카드 identity 조회. pair/card 잠금 뒤 정본으로 재검증한다.
        MeetingCardRepository.MeetingCardIdentity initialCard = meetingCardRepository.findIdentityById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        // 잠금 전 기본 참여자·DIRECT 구조 검증. 비참여자는 #148과 동일하게 존재 은닉 404.
        if (!participantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        requireDirectRoom(initialCard.getRoomId());
        List<Long> participantPetIds = participantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                ? participantPetIds.get(1)
                : participantPetIds.get(0);

        // 인증·참여 권한과 Chat 접근성을 Pair/Card 잠금 전에 우선 검증한다.
        chatQueryService.requireParticipant(initialCard.getRoomId(), actor.petId());

        // Pair 잠금 후 locked actor/target 을 authoritative 하게 재검증한다.
        InteractionPairContext lockedPair = pairLockService.lockInteractionPair(
                actor.petId(), counterpartPetId);
        requireLockedActor(userId, actor, lockedPair);
        requireActiveTarget(lockedPair);

        // 같은 소유자의 두 Pet 으로 Code 확정할 수 없다.
        if (Objects.equals(lockedPair.sourcePet().ownerUserId(),
                lockedPair.targetPet().ownerUserId())) {
            throw new BusinessException(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }

        // Pair Lock 뒤 authoritative 카드 행 잠금.
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        requireAuthoritativeCard(cardId, card, actor.petId(), counterpartPetId);
        chatQueryService.requireParticipant(card.getRoomId(), actor.petId());

        // OPEN 검증.
        if (card.getStatus() != MeetingCardStatus.OPEN) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_OPEN);
        }

        return new Access(actor, counterpartPetId, card);
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
        List<Long> participantPetIds = participantRepository.findPetIdsByMeetingCardId(cardId);
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

    /** 저장된 CODE_REQUIRED 가 서버 정본 gate. Expiry 가 EXPIRED 로 전이한 뒤에는 여기서 거절된다. */
    private void requireCodeRequired(long cardId) {
        boolean lowAccuracyRecorded = verificationRepository.findAllByMeetingCardId(cardId).stream()
                .anyMatch(v -> v.getStatus() == MeetingVerificationStatus.CODE_REQUIRED);
        if (!lowAccuracyRecorded) {
            throw new BusinessException(ErrorCode.MEETING_CODE_REQUIRED);
        }
    }

    /** 새 CODE mutation은 종료 시각을 포함해 시작하지 않는다: {@code receivedAt >= deadline}. */
    private void requireServerReceiveBeforeDeadline(Instant meetAt, Instant receivedAt) {
        if (!receivedAt.isBefore(meetAt.plus(meetingVerificationProperties.meetingTimeWindow()))) {
            throw new BusinessException(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        }
    }

    private ConfirmationCodeResult confirmed(Meeting meeting) {
        return new ConfirmationCodeResult(meeting.getMeetingCardId(), "CONFIRMED", meeting.getId(),
                meeting.getVerificationMethod(), meeting.getConfirmedAt());
    }

    private ConfirmationCodeResult alreadyConfirmedCodeMeeting(Meeting meeting) {
        if (meeting.getVerificationMethod() != MeetingVerificationMethod.CODE) {
            throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
        }
        return confirmed(meeting);
    }

    /** MeetingCardService.requireLockedActor 와 같은 검증. 잠긴 pair 가 요청자와 일치하는지 본다. */
    private void requireLockedActor(Long userId, ActivePetContext actor, InteractionPairContext locked) {
        if (!Objects.equals(locked.sourceUser().userId(), userId)
                || !Objects.equals(actor.ownerUserId(), userId)
                || !Objects.equals(locked.sourcePet().ownerUserId(), userId)
                || !Objects.equals(locked.sourceUser().activePetId(), actor.petId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (locked.sourceUser().accountStatus() != AccountStatus.ACTIVE
                || locked.sourcePet().status() != PetStatus.ACTIVE
                || locked.sourcePet().deletedAt() != null) {
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

    private record Access(ActivePetContext actor, long counterpartPetId, MeetingCard card) {
    }
}
