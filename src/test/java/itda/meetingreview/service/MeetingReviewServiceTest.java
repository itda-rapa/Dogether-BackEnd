package itda.meetingreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingreview.domain.Footprint;
import itda.meetingreview.domain.MeetingReview;
import itda.meetingreview.dto.MeetingReviewSubmitCommand;
import itda.meetingreview.dto.MeetingReviewSubmitResult;
import itda.meetingreview.repository.FootprintRepository;
import itda.meetingreview.repository.MeetingReviewRepository;
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.repository.MeetingRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingReviewServiceTest {

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long CARD_ID = 100L;
    private static final long MEETING_ID = 200L;
    private static final long ROOM_ID = 300L;
    private static final Instant NOW = Instant.parse("2026-08-20T09:10:00Z");
    private static final LocalDate KST_DATE = LocalDate.of(2026, 8, 20);
    private static final String PLACE_TAG = "공원";

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingCardRepository meetingCardRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private MeetingReviewRepository meetingReviewRepository;
    @Mock
    private FootprintRepository footprintRepository;
    @Mock
    private EntityManager entityManager;

    private MeetingReviewService service;
    private ActivePetContext actor;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new MeetingReviewService(
                activePetQueryService,
                meetingRepository,
                meetingCardRepository,
                meetingParticipantRepository,
                interactionPairLockService,
                meetingReviewRepository,
                footprintRepository,
                entityManager,
                clock);
        actor = new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false);
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(actor);
    }

    // ── 권한·상태 ───────────────────────────────────────────────────────────

    @Test
    void requiresActivePetBeforeAnyLookup() {
        when(activePetQueryService.requireActivePet(USER_1))
                .thenThrow(new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        verifyNoInteractions(meetingRepository);
    }

    @Test
    void rejectsMissingMeeting() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingReviewRepository, footprintRepository);
    }

    @Test
    void rejectsNonParticipant() {
        stubMeeting();
        // 정확히 2명이지만 요청자 Pet 이 아니다.
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_2, 33L));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_PARTICIPANT);

        assertThat(ErrorCode.REVIEW_NOT_PARTICIPANT.getStatus().value()).isEqualTo(403);
        assertThat(ErrorCode.REVIEW_NOT_PARTICIPANT.getDescription())
                .isEqualTo("약속 참여 반려견만 후기를 작성할 수 있습니다.")
                .doesNotContain("위치");
        assertThat(ErrorCode.MEETING_NOT_PARTICIPANT.getDescription())
                .isEqualTo("약속 참여 반려견만 위치를 제출할 수 있습니다.");

        verifyNoInteractions(interactionPairLockService, meetingReviewRepository, footprintRepository);
    }

    @Test
    void rejectsCanceledCard() {
        MeetingCard canceled = new MeetingCard(ROOM_ID, PET_1, null, MeetingCardType.WALK, "장소", NOW);
        canceled.cancel(PET_2, NOW);
        stubMeeting();
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2)).thenReturn(lockedPair());
        when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(canceled));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_CARD_NOT_OPEN);

        assertThat(ErrorCode.REVIEW_CARD_NOT_OPEN.getStatus().value()).isEqualTo(409);
        assertThat(ErrorCode.REVIEW_CARD_NOT_OPEN.getDescription())
                .isEqualTo("취소되거나 닫힌 약속 카드에는 후기를 작성할 수 없습니다.")
                .doesNotContain("위치");
        assertThat(ErrorCode.MEETING_CARD_NOT_OPEN.getDescription())
                .isEqualTo("취소된 약속 카드에는 위치를 제출할 수 없습니다.");
        assertThat(ErrorCode.MEETING_CODE_REQUIRED.getDescription())
                .isEqualTo("위치 정확도 부족으로 확인 코드 방식이 필요합니다.");

        verifyNoInteractions(meetingReviewRepository, footprintRepository);
    }

    // ── Active Pet lock/revalidation ───────────────────────────────────────

    @Test
    void lockedSourceActivePetIdChangeIsConcurrentUpdateConflict() {
        stubMeeting();
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        // 잠금 뒤 source User 의 activePetId 가 바뀌었다.
        InteractionPairContext changed = new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, 99L, "user#1"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#2"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2)).thenReturn(changed);

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);

        verify(meetingCardRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(meetingReviewRepository, footprintRepository);
    }

    @Test
    void lockedSourceInactiveIsActivePetRequired() {
        stubMeeting();
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        // 잠금 뒤 source User 가 비활성화되었다.
        InteractionPairContext inactive = new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.SUSPENDED, PET_1, "user#1"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#2"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2)).thenReturn(inactive);

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        verify(meetingCardRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(meetingReviewRepository, footprintRepository);
    }

    @Test
    void counterpartInactiveOrDeletedDoesNotBlockReview() {
        stubMeeting();
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        // 상대(target) User/Pet 의 나중 inactive/deleted 는 후기 차단 정책이 아니다.
        InteractionPairContext counterpartInactive = new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#1"),
                new LockedUserContext(USER_2, AccountStatus.SUSPENDED, PET_2, "user#2"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.SUSPENDED,
                        Instant.parse("2026-08-01T00:00:00Z")));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2)).thenReturn(counterpartInactive);
        when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(openCard()));
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenAnswer(inv -> returnReviewWithId(inv.getArgument(0), 71L));
        when(footprintRepository.insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, KST_DATE)).thenReturn(1);
        when(footprintRepository.findByReceiverPetIdAndEarnedDate(PET_1, KST_DATE))
                .thenReturn(Optional.of(newFootprint(81L)));

        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID, command(UUID.randomUUID()));

        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.footprint().granted()).isTrue();
    }

    // ── 정상 제출 ───────────────────────────────────────────────────────────

    @Test
    void submitsReviewAndGrantsNewFootprintForGpsMeeting() {
        stubHappyPath(MeetingVerificationMethod.GPS);

        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID, command(UUID.randomUUID()));

        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.meetingId()).isEqualTo(MEETING_ID);
        assertThat(result.placeTag()).isEqualTo(PLACE_TAG);
        assertThat(result.content()).isEqualTo("즐겁게 산책했어요.");
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.footprint().granted()).isTrue();
        assertThat(result.footprint().duplicateDay()).isFalse();
        assertThat(result.footprint().footprintId()).isEqualTo(81L);
        assertThat(result.footprint().earnedDate()).isEqualTo(KST_DATE);

        InOrder order = inOrder(meetingReviewRepository, footprintRepository);
        order.verify(meetingReviewRepository).saveAndFlush(any(MeetingReview.class));
        order.verify(footprintRepository).insertIfDailyAbsent(
                MEETING_ID, PET_1, PET_2, KST_DATE);
    }

    @Test
    void submitsReviewForCodeConfirmedMeeting() {
        // 검증 방식(GPS/CODE)은 후기 권한에 영향을 주지 않는다.
        stubHappyPath(MeetingVerificationMethod.CODE);

        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID, command(UUID.randomUUID()));

        assertThat(result.footprint().granted()).isTrue();
    }

    @Test
    void contentIsOptional() {
        stubHappyPath(MeetingVerificationMethod.GPS);

        MeetingReviewSubmitResult result =
                service.submit(USER_1, MEETING_ID, new MeetingReviewSubmitCommand(UUID.randomUUID(), PLACE_TAG, null));

        assertThat(result.placeTag()).isEqualTo(PLACE_TAG);
        assertThat(result.content()).isNull();
        assertThat(result.footprint().granted()).isTrue();
    }

    @Test
    void dailyConflictReusesExistingFootprintWithoutInserting() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenAnswer(inv -> returnReviewWithId(inv.getArgument(0), 71L));
        // 이미 그날(Asia/Seoul) 발자국이 있어 ON CONFLICT 가 0 을 돌려준다(순차·동시 동일).
        when(footprintRepository.insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, KST_DATE))
                .thenReturn(0);
        when(footprintRepository.findByReceiverPetIdAndEarnedDate(PET_1, KST_DATE))
                .thenReturn(Optional.of(newFootprint(81L)));

        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID, command(UUID.randomUUID()));

        // 후기는 정상 저장되고, 발자국은 기존 한 건 재사용으로 수렴한다.
        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.footprint().granted()).isFalse();
        assertThat(result.footprint().duplicateDay()).isTrue();
        assertThat(result.footprint().footprintId()).isEqualTo(81L);
        assertThat(result.footprint().earnedDate()).isEqualTo(KST_DATE);
        verify(footprintRepository, never()).saveAndFlush(any(Footprint.class));
    }

    @Test
    void nonDailyFootprintViolationPropagates() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenAnswer(inv -> returnReviewWithId(inv.getArgument(0), 71L));
        when(footprintRepository.insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, KST_DATE))
                .thenThrow(uniqueViolation("footprints_meeting_id_fkey"));

        // 일반 DB 예외는 뭉뚱그려 성공 처리하지 않는다. → 트랜잭션 롤백(후기 포함).
        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(footprintRepository, never()).findByReceiverPetIdAndEarnedDate(anyLong(), any());
    }

    // ── clientRequestId 멱등·충돌 ──────────────────────────────────────────

    @Test
    void sameClientRequestIdWithSameMeetingPetAndPayloadIsIdempotent() {
        UUID clientRequestId = UUID.randomUUID();
        MeetingReview existing =
                new MeetingReview(MEETING_ID, PET_1, PLACE_TAG, clientRequestId, "즐겁게 산책했어요.");
        ReflectionTestUtils.setField(existing, "id", 71L);
        ReflectionTestUtils.setField(existing, "createdAt", NOW);
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(footprintRepository.findByMeetingIdAndReceiverPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.of(newFootprint(81L)));

        MeetingReviewSubmitResult result =
                service.submit(USER_1, MEETING_ID,
                        new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요."));

        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.placeTag()).isEqualTo(PLACE_TAG);
        assertThat(result.footprint().granted()).isFalse();
        assertThat(result.footprint().duplicateDay()).isFalse();
        assertThat(result.footprint().footprintId()).isEqualTo(81L);
        verify(meetingReviewRepository, never()).saveAndFlush(any(MeetingReview.class));
        verify(footprintRepository, never()).saveAndFlush(any(Footprint.class));
    }

    @Test
    void sameClientRequestIdOnAnotherMeetingConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        MeetingReview existing = new MeetingReview(999L, PET_1, PLACE_TAG, clientRequestId, "다른 만남 후기");
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID,
                new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요.")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);
    }

    @Test
    void sameClientRequestIdWithDifferentContentConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        MeetingReview existing =
                new MeetingReview(MEETING_ID, PET_1, PLACE_TAG, clientRequestId, "원래 후기");
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID,
                new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "다른 후기")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);
    }

    @Test
    void sameClientRequestIdWithDifferentPlaceTagConflicts() {
        UUID clientRequestId = UUID.randomUUID();
        MeetingReview existing =
                new MeetingReview(MEETING_ID, PET_1, "공원", clientRequestId, "즐겁게 산책했어요.");
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID,
                new MeetingReviewSubmitCommand(clientRequestId, "카페", "즐겁게 산책했어요.")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);
    }

    @Test
    void existingReviewWithNewClientRequestIdIsRejected() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.of(
                        new MeetingReview(MEETING_ID, PET_1, PLACE_TAG, UUID.randomUUID(), "기존")));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);

        verify(meetingReviewRepository, never()).saveAndFlush(any(MeetingReview.class));
        verifyNoInteractions(footprintRepository);
    }

    // ── 후기 저장 시 동시 중복 → 전용 409 ──────────────────────────────────

    @Test
    void reviewPetUniqueViolationTranslatesToAlreadyExists() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenThrow(uniqueViolation("uk_meeting_review_pet"));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
    }

    @Test
    void reviewClientRequestUniqueViolationTranslatesToRequestConflict() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenThrow(uniqueViolation("uk_meeting_review_client_request"));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUEST_CONFLICT);
    }

    @Test
    void otherReviewViolationPropagates() {
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenThrow(uniqueViolation("uk_meeting_review_unknown"));

        assertThatThrownBy(() -> service.submit(USER_1, MEETING_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── KST 자정 경계 멱등 ─────────────────────────────────────────────────

    @Test
    void replayAcrossKstMidnightReusesOriginalDailyFootprint() {
        UUID clientRequestId = UUID.randomUUID();
        Instant beforeMidnight = Instant.parse("2026-08-20T14:59:59Z"); // KST 8/20 23:59:59
        Instant afterMidnight = Instant.parse("2026-08-20T15:00:01Z"); // KST 8/21 00:00:01
        LocalDate originalDate = LocalDate.of(2026, 8, 20);

        // 최초 후기는 KST 자정 전(8/20)에 적립됐고, 그날 발자국은 다른 Meeting 에서 이미 존재한다.
        MeetingReview existing =
                new MeetingReview(MEETING_ID, PET_1, PLACE_TAG, clientRequestId, "즐겁게 산책했어요.");
        ReflectionTestUtils.setField(existing, "id", 71L);
        ReflectionTestUtils.setField(existing, "createdAt", beforeMidnight);
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        // 이 Meeting·Pet 발자국은 없고, 그날 일일 발자국만 다른 Meeting 에서 존재한다.
        when(footprintRepository.findByMeetingIdAndReceiverPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        Footprint daily = new Footprint(999L, PET_1, PET_2, originalDate);
        ReflectionTestUtils.setField(daily, "id", 81L);
        when(footprintRepository.findByReceiverPetIdAndEarnedDate(PET_1, originalDate))
                .thenReturn(Optional.of(daily));

        // 자정 이후 재시도: 같은 clientRequestId + 동일 payload.
        clock.set(afterMidnight);
        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID,
                new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요."));

        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.createdAt()).isEqualTo(beforeMidnight);
        assertThat(result.footprint().footprintId()).isEqualTo(81L);
        assertThat(result.footprint().duplicateDay()).isTrue();
        assertThat(result.footprint().earnedDate()).isEqualTo(originalDate);
        // 최초 후기 적립일(8/20)로 조회하고, 자정 이후 오늘(8/21)로 조회하지 않는다.
        verify(footprintRepository).findByReceiverPetIdAndEarnedDate(PET_1, originalDate);
        verify(footprintRepository, never())
                .findByReceiverPetIdAndEarnedDate(PET_1, LocalDate.of(2026, 8, 21));
        verify(meetingReviewRepository, never()).saveAndFlush(any(MeetingReview.class));
        verify(footprintRepository, never())
                .insertIfDailyAbsent(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void replayAcrossKstMidnightKeepsMeetingFootprintWhenGrantedFirstTime() {
        UUID clientRequestId = UUID.randomUUID();
        Instant beforeMidnight = Instant.parse("2026-08-20T14:59:59Z");
        Instant afterMidnight = Instant.parse("2026-08-20T15:00:01Z");
        LocalDate originalDate = LocalDate.of(2026, 8, 20);

        MeetingReview existing =
                new MeetingReview(MEETING_ID, PET_1, PLACE_TAG, clientRequestId, "즐겁게 산책했어요.");
        ReflectionTestUtils.setField(existing, "id", 71L);
        ReflectionTestUtils.setField(existing, "createdAt", beforeMidnight);
        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        // 최초 요청에서 이 Meeting·Pet 발자국이 새로 만들어졌다(granted=true).
        Footprint meetingFootprint = new Footprint(MEETING_ID, PET_1, PET_2, originalDate);
        ReflectionTestUtils.setField(meetingFootprint, "id", 81L);
        when(footprintRepository.findByMeetingIdAndReceiverPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.of(meetingFootprint));

        clock.set(afterMidnight);
        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID,
                new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요."));

        assertThat(result.reviewId()).isEqualTo(71L);
        assertThat(result.footprint().footprintId()).isEqualTo(81L);
        assertThat(result.footprint().granted()).isFalse();
        assertThat(result.footprint().duplicateDay()).isFalse();
        assertThat(result.footprint().earnedDate()).isEqualTo(originalDate);
        verify(meetingReviewRepository, never()).saveAndFlush(any(MeetingReview.class));
        verify(footprintRepository, never())
                .insertIfDailyAbsent(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void newReviewUsesPersistedReviewCreatedAtForFootprintDate() {
        // auditing 이 저장한 createdAt 과 주입 Clock 이 자정을 사이에 두고 다를 수 있다.
        // 발자국 적립일은 이후 멱등 재시도의 정본인 review.createdAt 을 따라야 한다.
        Instant afterMidnight = Instant.parse("2026-08-20T15:00:01Z"); // KST 8/21 00:00:01
        LocalDate persistedReviewDate = LocalDate.of(2026, 8, 20);
        clock.set(afterMidnight);

        stubMeeting();
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenAnswer(inv -> returnReviewWithId(inv.getArgument(0), 71L));
        when(footprintRepository.insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, persistedReviewDate))
                .thenReturn(1);
        Footprint granted = new Footprint(MEETING_ID, PET_1, PET_2, persistedReviewDate);
        ReflectionTestUtils.setField(granted, "id", 81L);
        when(footprintRepository.findByReceiverPetIdAndEarnedDate(PET_1, persistedReviewDate))
                .thenReturn(Optional.of(granted));

        MeetingReviewSubmitResult result = service.submit(USER_1, MEETING_ID, command(UUID.randomUUID()));

        assertThat(result.footprint().granted()).isTrue();
        assertThat(result.footprint().earnedDate()).isEqualTo(persistedReviewDate);
        verify(footprintRepository).insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, persistedReviewDate);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubMeeting() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(MeetingVerificationMethod.GPS)));
    }

    private void stubLockedPairAndCard() {
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2)).thenReturn(lockedPair());
        when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(openCard()));
    }

    private void stubHappyPath(MeetingVerificationMethod method) {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(method)));
        stubLockedPairAndCard();
        when(meetingReviewRepository.findByClientRequestId(any(UUID.class))).thenReturn(Optional.empty());
        when(meetingReviewRepository.findByMeetingIdAndReviewerPetId(MEETING_ID, PET_1))
                .thenReturn(Optional.empty());
        when(meetingReviewRepository.saveAndFlush(any(MeetingReview.class)))
                .thenAnswer(inv -> returnReviewWithId(inv.getArgument(0), 71L));
        when(footprintRepository.insertIfDailyAbsent(MEETING_ID, PET_1, PET_2, KST_DATE))
                .thenReturn(1);
        when(footprintRepository.findByReceiverPetIdAndEarnedDate(PET_1, KST_DATE))
                .thenReturn(Optional.of(newFootprint(81L)));
    }

    private InteractionPairContext lockedPair() {
        return new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#1"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#2"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null));
    }

    private Meeting meeting(MeetingVerificationMethod method) {
        Double distanceMeters = method == MeetingVerificationMethod.GPS ? 10.0 : null;
        return new Meeting(CARD_ID, method, NOW, distanceMeters);
    }

    private MeetingCard openCard() {
        return new MeetingCard(ROOM_ID, PET_1, null, MeetingCardType.WALK, "중앙공원", NOW);
    }

    private MeetingReview returnReviewWithId(MeetingReview review, long id) {
        ReflectionTestUtils.setField(review, "id", id);
        ReflectionTestUtils.setField(review, "createdAt", NOW);
        return review;
    }

    private Footprint newFootprint(long id) {
        Footprint footprint = new Footprint(MEETING_ID, PET_1, PET_2, KST_DATE);
        ReflectionTestUtils.setField(footprint, "id", id);
        return footprint;
    }

    private MeetingReviewSubmitCommand command(UUID clientRequestId) {
        return new MeetingReviewSubmitCommand(clientRequestId, PLACE_TAG, "즐겁게 산책했어요.");
    }

    private DataIntegrityViolationException uniqueViolation(String constraintName) {
        return new DataIntegrityViolationException("unique violation",
                new ConstraintViolationException(
                        "constraint violation", new SQLException("violation"), constraintName));
    }

    /** 테스트에서 KST 자정 경계를 넘기도록 시간을 이동할 수 있는 Clock. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
