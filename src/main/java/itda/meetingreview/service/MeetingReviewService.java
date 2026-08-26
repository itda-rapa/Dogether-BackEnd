package itda.meetingreview.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingreview.domain.Footprint;
import itda.meetingreview.domain.MeetingReview;
import itda.meetingreview.dto.MeetingReviewSubmitCommand;
import itda.meetingreview.dto.MeetingReviewSubmitResult;
import itda.meetingreview.dto.ReviewFootprintResult;
import itda.meetingreview.repository.FootprintRepository;
import itda.meetingreview.repository.MeetingReviewRepository;
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.repository.MeetingRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만남 후기·발자국(#150). 실제로 확정된 {@link Meeting} 이 있는 경우에만 후기를 쓸 수 있고,
 * 권한은 기존 {@link ActivePetQueryService} + MeetingCard 참여자 계약으로 판정한다.
 *
 * <p>source Active Pet 동시 쓰기는 기존 {@link InteractionPairLockService} lock order(User
 * 오름차순 → Pet 오름차순)를 재사용한다(#148과 동일한 구조적 lock 계약). 확정 Meeting →
 * 참여자·정확히 2명 구조 확인 → Pair Lock → 잠긴 source User/Pet 재검증 →
 * Card {@code FOR UPDATE} → 잠긴 카드 기준 참여자·OPEN 재검증 순서를 지키며, 잠금 뒤
 * {@code activePetId} 가 바뀌면 {@code CONCURRENT_UPDATE_CONFLICT} 로 수렴한다. 상대 Pet 의
 * 나중 inactive/deleted 는 후기 차단 정책으로 보지 않는다(잠긴 source User/Pet 만 재검증).
 *
 * <p>후기 저장과 "신규 발자국 생성 여부 판단·생성"은 하나의 {@code @Transactional} 로 처리한다.
 * 후기 저장이 실패하면 발자국도 남지 않고, 신규 발자국 생성이 실패하면 후기도 롤백된다.
 * 잠금·재검증 단계에서 실패하면 후기·발자국 쓰기가 전혀 일어나지 않는다(0건).
 *
 * <p>발자국의 하루 기준은 Asia/Seoul 이며 후기 제출 시각을 KST 로 변환해 적립한다. 같은 Pet 의
 * 같은 KST 날짜 발자국은 최대 한 건이고, 같은 날 다른 Meeting 의 후기는 저장할 수 있다. 이미
 * 그날 발자국이 있으면 새 행을 만들지 않고 기존 한 건을 재사용한다
 * ({@code uk_footprint_pet_date} 가 최종 방어선). 동시 일일 경합은 삽입 시
 * {@code ON CONFLICT DO NOTHING} 으로 "후기는 정상 저장, 발자국은 기존 한 건 재사용"에
 * 수렴하며, 그 외 일반 DB 예외는 뭉뚱그려 성공 처리하지 않는다.
 * 응답의 {@code footprint.granted} 는 오직 현재 HTTP 요청에서 새 Footprint 행을 INSERT했는지를
 * 뜻한다. 따라서 최초 신규 적립만 {@code true} 이고, 멱등 replay와 기존 Meeting 발자국 수렴,
 * 같은 날 일일 발자국 재사용은 새 행이 없으므로 {@code false} 다. Review와 Footprint는 항상
 * 1:1 관계가 아니다.
 *
 * <p>같은 {@code (meeting_id, reviewer_pet_id)} 재작성은 {@code REVIEW_ALREADY_EXISTS}(409) 로
 * 거부하고, 같은 {@code clientRequestId} 재요청은 {@code placeTag}·{@code content} 가 모두
 * 같을 때만 멱등으로 기존 후기를 반환한다. GPS/CODE 검증 방식은 후기 권한에 영향을 주지
 * 않으며, MeetingCard lifecycle 과 MeetingVerification 상태는 건드리지 않는다.
 */
@Service
public class MeetingReviewService {

    private static final String REVIEW_PET_UNIQUE_CONSTRAINT = "uk_meeting_review_pet";
    private static final String REVIEW_CLIENT_REQUEST_UNIQUE_CONSTRAINT =
            "uk_meeting_review_client_request";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ActivePetQueryService activePetQueryService;
    private final MeetingRepository meetingRepository;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final MeetingReviewRepository meetingReviewRepository;
    private final FootprintRepository footprintRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    // 프로젝트 관행대로 주 생성자가 Clock 을 받지 않고 편의 생성자가 기본값을 넘긴다
    // (MeetingCardService 와 같은 형태).
    @Autowired
    public MeetingReviewService(ActivePetQueryService activePetQueryService,
                                MeetingRepository meetingRepository,
                                MeetingCardRepository meetingCardRepository,
                                MeetingParticipantRepository meetingParticipantRepository,
                                InteractionPairLockService interactionPairLockService,
                                MeetingReviewRepository meetingReviewRepository,
                                FootprintRepository footprintRepository,
                                EntityManager entityManager) {
        this(activePetQueryService, meetingRepository, meetingCardRepository,
                meetingParticipantRepository, interactionPairLockService,
                meetingReviewRepository, footprintRepository, entityManager,
                Clock.systemUTC());
    }

    MeetingReviewService(ActivePetQueryService activePetQueryService,
                         MeetingRepository meetingRepository,
                         MeetingCardRepository meetingCardRepository,
                         MeetingParticipantRepository meetingParticipantRepository,
                         InteractionPairLockService interactionPairLockService,
                         MeetingReviewRepository meetingReviewRepository,
                         FootprintRepository footprintRepository,
                         EntityManager entityManager,
                         Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.meetingRepository = meetingRepository;
        this.meetingCardRepository = meetingCardRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.interactionPairLockService = interactionPairLockService;
        this.meetingReviewRepository = meetingReviewRepository;
        this.footprintRepository = footprintRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public MeetingReviewSubmitResult submit(Long userId,
                                            long meetingId,
                                            MeetingReviewSubmitCommand command) {
        // 1. 최초 ActivePetContext 확보. 이후 Pair Lock 뒤 잠긴 source 로 재검증한다.
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 2. 확정 Meeting / 카드 / 참여자 / 정확히 2명 구조 확인 + counterpart 식별.
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_FOUND));
        long cardId = meeting.getMeetingCardId();
        long counterpartPetId = counterpartPetId(cardId, actor.petId());

        // 3. Pair Lock 후 잠긴 source User/Pet 만 재검증한다. 상대 Pet 의 나중
        //    inactive/deleted 는 후기 차단 정책으로 보지 않는다.
        InteractionPairContext lockedPair = interactionPairLockService.lockInteractionPair(
                actor.petId(), counterpartPetId);
        requireLockedActor(userId, actor, lockedPair);

        // 4. Pair Lock → Card FOR UPDATE 순서 유지. 잠긴 카드 기준 참여자·OPEN 재검증.
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));
        requireReviewableCard(cardId, card, actor.petId(), counterpartPetId);

        Optional<MeetingReview> byRequestId =
                meetingReviewRepository.findByClientRequestId(command.clientRequestId());
        if (byRequestId.isPresent()) {
            MeetingReview existing = byRequestId.get();
            if (existing.getMeetingId().equals(meetingId)
                    && existing.getReviewerPetId().equals(actor.petId())
                    && Objects.equals(existing.getPlaceTag(), command.placeTag())
                    && Objects.equals(existing.getContent(), command.content())) {
                // 같은 clientRequestId + 같은 Meeting/Pet + 동일 placeTag/content: 멱등으로 기존 후기 반환.
                return respond(existing, footprintFor(existing, actor.petId()));
            }
            // Meeting/Pet/placeTag/content 중 하나라도 다르면 요청 충돌.
            throw new BusinessException(ErrorCode.REVIEW_REQUEST_CONFLICT);
        }

        if (meetingReviewRepository
                .findByMeetingIdAndReviewerPetId(meetingId, actor.petId())
                .isPresent()) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        MeetingReview review = saveReview(meetingId, actor.petId(), command);
        ReviewFootprintResult footprint = grantFootprint(review, actor.petId(), counterpartPetId);
        return respond(review, footprint);
    }

    /**
     * 후기 저장. 쓰기를 즉시 flush 해 동시 중복(같은 Meeting·Pet 또는 같은 clientRequestId)을
     * 이 지점에서 드러내고 각각 전용 409 로 번역한다. 다른 제약은 번역하지 않고 그대로 흘려보낸다.
     */
    private MeetingReview saveReview(long meetingId,
                                     long petId,
                                     MeetingReviewSubmitCommand command) {
        MeetingReview review = new MeetingReview(
                meetingId, petId, command.placeTag(), command.clientRequestId(), command.content());
        try {
            MeetingReview saved = meetingReviewRepository.saveAndFlush(review);
            // 최초 응답도 DB가 보존한 timestamp 정밀도를 사용해야 replay의 createdAt과 같다.
            entityManager.refresh(saved);
            return saved;
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception, REVIEW_PET_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
            }
            if (isUniqueViolation(exception, REVIEW_CLIENT_REQUEST_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.REVIEW_REQUEST_CONFLICT);
            }
            throw exception;
        }
    }

    /**
     * 신규 발자국 생성 여부 판단·생성. 같은 트랜잭션 안에서 후기 저장 뒤에 실행된다.
     *
     * <p>같은 Pet·같은 KST 날짜 발자국이 이미 있으면 {@code ON CONFLICT DO NOTHING} 이
     * 문장 단위로 충돌을 흡수하고 0 을 돌려주므로(PostgreSQL 은 문장 오류 시 트랜잭션
     * 전체가 aborted 되기 때문), 후기는 정상 저장되고 기존 한 건을 재사용한다. 삽입에
     * 성공하면 1 을 돌려받아 신규 적립으로 응답한다. 그 외 일반 DB 예외(예: FK 위반)는
     * 그대로 흘려보내 트랜잭션이 롤백되게 한다(후기 포함).
     */
    private ReviewFootprintResult grantFootprint(MeetingReview review,
                                                 long petId,
                                                 long counterpartPetId) {
        // 후기와 발자국의 KST 일자는 같은 영속화 시각을 정본으로 삼는다. auditing 시각과
        // 주입 Clock 호출이 자정을 사이에 두고 달라져 재시도 조회가 어긋나는 것을 막는다.
        LocalDate earnedDate = review.getCreatedAt().atZone(SEOUL).toLocalDate();
        int inserted = footprintRepository.insertIfDailyAbsent(
                review.getMeetingId(), petId, counterpartPetId, earnedDate);
        Footprint footprint = footprintRepository
                .findByReceiverPetIdAndEarnedDate(petId, earnedDate)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        return inserted == 1
                ? new ReviewFootprintResult(true, footprint.getId(), false, earnedDate)
                : new ReviewFootprintResult(false, footprint.getId(), true, earnedDate);
    }

    /**
     * 멱등 재요청 응답용 발자국 정보. 우선순위는 (1) 이 Meeting·Pet 발자국, (2) 없으면
     * 최초 후기 적립일({@code review.getCreatedAt()} 을 Asia/Seoul 로 변환)의 일일 발자국.
     *
     * <p>현재 시각을 쓰지 않으므로 KST 자정을 넘긴 재시도도 최초 후기와
     * 같은 발자국·적립일을 돌려준다. 자정 경계를 넘긴 뒤에도 같은 {@code clientRequestId} +
     * 동일 payload 재시도의 응답이 최초 사실과 일관된다.
     */
    private ReviewFootprintResult footprintFor(MeetingReview review, long petId) {
        Optional<Footprint> byMeeting =
                footprintRepository.findByMeetingIdAndReceiverPetId(review.getMeetingId(), petId);
        if (byMeeting.isPresent()) {
            Footprint footprint = byMeeting.get();
            return new ReviewFootprintResult(false, footprint.getId(), false, footprint.getEarnedDate());
        }
        LocalDate earnedDate = review.getCreatedAt().atZone(SEOUL).toLocalDate();
        Optional<Footprint> daily =
                footprintRepository.findByReceiverPetIdAndEarnedDate(petId, earnedDate);
        return daily.map(footprint ->
                        new ReviewFootprintResult(false, footprint.getId(), true, footprint.getEarnedDate()))
                .orElse(new ReviewFootprintResult(false, null, false, null));
    }

    /**
     * 잠금 전 구조 확인: 카드 참여자가 정확히 2명이고 요청자가 그 중 한 명인지 확인한 뒤
     * 상대 Pet 을 식별한다. 비참여자는 {@code REVIEW_NOT_PARTICIPANT}(403), 참여자 수가
     * 2명이 아닌 확정 Meeting 은 {@code MEETING_CARD_NOT_FOUND}(404) 로 거절한다.
     */
    private long counterpartPetId(long cardId, long petId) {
        List<Long> participantPetIds = meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }
        if (!participantPetIds.contains(petId)) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_PARTICIPANT);
        }
        return participantPetIds.get(0).equals(petId)
                ? participantPetIds.get(1)
                : participantPetIds.get(0);
    }

    /**
     * MeetingCardService.requireLockedActor 와 같은 검증. 잠긴 pair 의 source 가 요청자와
     * 일치하는지 본다. {@code activePetId} 가 잠금 뒤 바뀌었으면 {@code CONCURRENT_UPDATE_CONFLICT},
     * 잠긴 source User/Pet 이 ACTIVE 가 아니면 {@code ACTIVE_PET_REQUIRED} 다.
     */
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

    /** 잠긴 카드 기준 참여자·OPEN 재검증. 실패하면 후기·발자국 쓰기 없이 거절한다. */
    private void requireReviewableCard(long cardId,
                                       MeetingCard card,
                                       long actorPetId,
                                       long counterpartPetId) {
        List<Long> participantPetIds = meetingParticipantRepository.findPetIdsByMeetingCardId(cardId);
        if (participantPetIds.size() != 2
                || !participantPetIds.contains(actorPetId)
                || !participantPetIds.contains(counterpartPetId)) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_PARTICIPANT);
        }
        if (card.getStatus() == MeetingCardStatus.CANCELED) {
            throw new BusinessException(ErrorCode.REVIEW_CARD_NOT_OPEN);
        }
    }

    private MeetingReviewSubmitResult respond(MeetingReview review, ReviewFootprintResult footprint) {
        return new MeetingReviewSubmitResult(
                review.getId(), review.getMeetingId(), review.getPlaceTag(), review.getContent(),
                review.getCreatedAt(), footprint);
    }

    /**
     * Hibernate/Spring 예외 cause chain 에서 constraint name 을 찾아 지정 제약 위반인지 판별한다.
     * FriendRequestCommandService/ChatMessageService 와 같은 프로젝트 패턴이다.
     */
    private boolean isUniqueViolation(DataIntegrityViolationException exception, String constraintName) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException constraintViolation) {
                return constraintName.equalsIgnoreCase(
                        constraintViolation.getConstraintName());
            }
            current = current.getCause();
        }
        return false;
    }
}
