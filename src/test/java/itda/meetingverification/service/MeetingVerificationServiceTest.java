package itda.meetingverification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.RoomStatus;
import itda.chat.domain.RoomType;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.service.ChatQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.location.dto.LocationAccuracyQuality;
import itda.location.dto.LocationAssessment;
import itda.location.dto.ValidatedLocation;
import itda.location.service.LocationService;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardType;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingVerificationServiceTest {

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long CARD_ID = 100L;
    private static final long OTHER_CARD_ID = 200L;
    private static final long ROOM_ID = 500L;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final String HMAC_SECRET =
            "test-meeting-verification-hmac-secret-at-least-32-bytes";

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private MeetingCardRepository meetingCardRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private ChatRoomRepository chatRoomRepository;
    @Mock
    private MeetingVerificationRepository meetingVerificationRepository;
    @Mock
    private MeetingVerificationRequestRepository meetingVerificationRequestRepository;
    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private ChatQueryService chatQueryService;
    @Mock
    private LocationService locationService;
    @Mock
    private Clock clock;

    private MeetingVerificationService service;
    private ActivePetContext actor;
    private MeetingVerificationFingerprint fingerprint;

    @BeforeEach
    void setUp() {
        MeetingVerificationProperties properties =
                new MeetingVerificationProperties(100, Duration.ofMinutes(5), Duration.ofHours(1),
                        HMAC_SECRET,
                        new MeetingVerificationProperties.Expiry(
                                true, Duration.ofSeconds(60), 50));
        lenient().when(clock.instant()).thenReturn(NOW);
        service = new MeetingVerificationService(
                activePetQueryService,
                meetingCardRepository,
                meetingParticipantRepository,
                chatRoomRepository,
                meetingVerificationRepository,
                meetingVerificationRequestRepository,
                meetingRepository,
                interactionPairLockService,
                chatQueryService,
                locationService,
                properties,
                clock);
        actor = new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false);
        fingerprint = new MeetingVerificationFingerprint(HMAC_SECRET);
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(actor);
    }

    // ── 권한·상태 오류는 Location 평가보다 먼저 ─────────────────────────────

    @Test
    void rejectsMissingCardBeforeLocationAssessment() {
        when(meetingCardRepository.findIdentityById(CARD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
        verifyNoInteractions(interactionPairLockService);
    }

    @Test
    void hidesNonParticipantAsNotFound() {
        doReturn(Optional.of(cardIdentity())).when(meetingCardRepository).findIdentityById(CARD_ID);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
    }

    @Test
    void hidesNonDirectCardAsNotFound() {
        doReturn(Optional.of(cardIdentity())).when(meetingCardRepository).findIdentityById(CARD_ID);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(true);
        ChatRoom room = room(RoomType.GROUP);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
    }

    @Test
    void rejectsCanceledCardBeforeLocationAssessment() {
        stubCanceledCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_OPEN);

        verifyNoInteractions(locationService);
    }

    @Test
    void hidesInactiveTargetAsNotFound() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                        new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                        new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_2, PetStatus.SUSPENDED, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
    }

    @Test
    void hidesInactiveTargetUserAsNotFound() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                        new LockedUserContext(USER_2, AccountStatus.SUSPENDED, PET_2, "user#0002"),
                        new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
    }

    @Test
    void hidesWithdrawnTargetUserAsNotFound() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                        new LockedUserContext(USER_2, AccountStatus.WITHDRAWN, PET_2, "user#0002"),
                        new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
    }

    @Test
    void rejectsInactiveSourceStates() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(USER_1, AccountStatus.SUSPENDED, PET_1, "user#0001"),
                        new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                        new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);

        verifyNoInteractions(locationService);
    }

    @Test
    void rejectsSameOwnerPair() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                        new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_2, "user#0001"),
                        new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_1, PetStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);

        verifyNoInteractions(locationService);
    }

    @Test
    void rejectsLockedPairMismatch() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(new InteractionPairContext(
                        new LockedUserContext(999L, AccountStatus.ACTIVE, PET_1, "user#999"),
                        new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                        new LockedPetContext(PET_1, 999L, PetStatus.ACTIVE, null),
                        new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null)));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);

        verifyNoInteractions(locationService);
    }

    @Test
    void blocksHideSubmitBeforeAnyLocationOrWrite() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        doThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND))
                .when(chatQueryService).requireParticipant(ROOM_ID, PET_1);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        verify(meetingCardRepository, never()).findByIdForUpdate(CARD_ID);
        verifyNoInteractions(locationService, meetingVerificationRequestRepository,
                meetingVerificationRepository, meetingRepository);
    }

    @Test
    void blockIsRecheckedAfterPairLockBeforeAuthoritativeCardLock() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        doNothing().doThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND))
                .when(chatQueryService).requireParticipant(ROOM_ID, PET_1);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        verify(interactionPairLockService).lockInteractionPair(PET_1, PET_2);
        verify(meetingCardRepository).findByIdForUpdate(CARD_ID);
        verifyNoInteractions(locationService, meetingVerificationRequestRepository,
                meetingVerificationRepository, meetingRepository);
    }

    @Test
    void submitUsesPairThenChatThenAuthoritativeCardLockOrder() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(lowAccuracy(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        service.submit(USER_1, CARD_ID, command(UUID.randomUUID()));

        InOrder order = inOrder(interactionPairLockService, chatQueryService, meetingCardRepository);
        order.verify(interactionPairLockService).lockInteractionPair(PET_1, PET_2);
        order.verify(chatQueryService).requireParticipant(ROOM_ID, PET_1);
        order.verify(meetingCardRepository).findByIdForUpdate(CARD_ID);
    }

    @Test
    void receivedAtIsCapturedBeforePairLockAndStoredAsSubmittedAt() {
        Instant receivedAt = NOW.minusSeconds(30);
        Instant afterLock = NOW.plusSeconds(30);
        when(clock.instant()).thenReturn(receivedAt, afterLock);
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenAnswer(invocation -> {
                    // Simulate time passing while waiting for the pair lock.
                    return lockedPair();
                });
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(lowAccuracy(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        ArgumentCaptor<MeetingVerification> saved = ArgumentCaptor.forClass(MeetingVerification.class);
        when(meetingVerificationRepository.saveAndFlush(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        service.submit(USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(saved.getValue().getSubmittedAt()).isEqualTo(receivedAt);
    }

    // ── Location 결과 처리 ─────────────────────────────────────────────────

    @Test
    void invalidLocationDoesNotRecordLedger() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any()))
                .thenThrow(new BusinessException(ErrorCode.LOCATION_INVALID));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOCATION_INVALID);

        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void lowAccuracyScrubsRawAndReturnsCodeRequired() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(lowAccuracy(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.codeRequired()).isTrue();
        assertThat(result.distanceMeters()).isNull();
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void firstAcceptableSubmitReturnsWaiting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.codeRequired()).isFalse();
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    // ── meetAt 약속 시간창 (경계 포함) ─────────────────────────────────────

    @Test
    void meetAtMinusOneHourBoundaryIsAccepted() {
        Instant capturedAt = NOW.minus(1, ChronoUnit.HOURS);
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID(), 37.5665, 126.978, 24.5, capturedAt));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
    }

    @Test
    void meetAtPlusOneHourBoundaryIsAccepted() {
        Instant capturedAt = NOW.plus(1, ChronoUnit.HOURS);
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.empty());

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID(), 37.5665, 126.978, 24.5, capturedAt));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
    }

    @Test
    void meetAtOutsideWindowIsRejectedWithoutStoringAnything() {
        Instant capturedAt = NOW.plus(1, ChronoUnit.HOURS).plusSeconds(1);
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));

        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID(), 37.5665, 126.978, 24.5, capturedAt)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void deadlineRejectsFreshSubmissionAfterMeetAtWindow() {
        // receivedAt = NOW(clock), meetAt = NOW - 2h → deadline = NOW - 1h < receivedAt.
        // capturedAt 은 과거 시간창 안이고 freshness 도 정상이어도 서버 수신 deadline 으로 거절한다.
        // 새 clientRequestId 는 ledger 조회(replay/conflict)를 먼저 통과한 뒤 deadline 을 검사한다.
        stubOpenCardWithRoomTypeAndMeetAt(RoomType.DIRECT, NOW.minus(2, ChronoUnit.HOURS));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        verify(meetingVerificationRequestRepository).findByClientRequestId(any(UUID.class));
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void expiredVerificationIsNotRevivedByNewRequestId() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        MeetingVerification expired = new MeetingVerification(
                CARD_ID, PET_1, UUID.randomUUID(), null, null, null, null,
                NOW.minus(2, ChronoUnit.HOURS), MeetingVerificationStatus.EXPIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        // EXPIRED 행은 replace 되지 않고 raw GPS 도 null 그대로다.
        assertThat(expired.getStatus()).isEqualTo(MeetingVerificationStatus.EXPIRED);
        assertThat(expired.getLatitude()).isNull();
        assertThat(expired.getLongitude()).isNull();
        assertThat(expired.getAccuracyMeters()).isNull();
        assertThat(expired.getCapturedAt()).isNull();
        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    // ── CODE_REQUIRED 종결 (GPS 재제출로 SUBMITTED 부활 금지) ───────────────

    @Test
    void codeRequiredPetRejectsNewGpsSubmission() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        MeetingVerification codeRequired = new MeetingVerification(
                CARD_ID, PET_1, UUID.randomUUID(), null, null, null, null,
                NOW.minusSeconds(10), MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(codeRequired));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_CODE_REQUIRED);

        // Location 재평가·ledger INSERT·verification replace·Meeting 생성이 모두 없어야 한다.
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        // 기존 CODE_REQUIRED 와 raw GPS null 이 그대로 유지된다.
        assertThat(codeRequired.getStatus()).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED);
        assertThat(codeRequired.getLatitude()).isNull();
        assertThat(codeRequired.getLongitude()).isNull();
        assertThat(codeRequired.getAccuracyMeters()).isNull();
        assertThat(codeRequired.getCapturedAt()).isNull();
    }

    @Test
    void codeRequiredPetRejectsNewGpsSubmissionEvenAfterDeadline() {
        // deadline(meetAt + 1h) 경과 뒤에도 CODE_REQUIRED Pet 의 새 GPS 는 CODE_REQUIRED 차단이
        // deadline 보다 먼저 적용되어 MEETING_VERIFICATION_CODE_REQUIRED 로 수렴한다.
        stubOpenCardWithRoomTypeAndMeetAt(RoomType.DIRECT, NOW.minus(2, ChronoUnit.HOURS));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        MeetingVerification codeRequired = new MeetingVerification(
                CARD_ID, PET_1, UUID.randomUUID(), null, null, null, null,
                NOW.minusSeconds(10), MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(codeRequired));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_CODE_REQUIRED);

        // Location 재평가·ledger INSERT·verification replace·Meeting 생성이 모두 없어야 한다.
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        // 기존 CODE_REQUIRED 와 raw GPS null 이 그대로 유지된다.
        assertThat(codeRequired.getStatus()).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED);
        assertThat(codeRequired.getLatitude()).isNull();
        assertThat(codeRequired.getLongitude()).isNull();
        assertThat(codeRequired.getAccuracyMeters()).isNull();
        assertThat(codeRequired.getCapturedAt()).isNull();
    }

    @Test
    void codeRequiredSameRequestReplaysAsCodeRequired() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(result.codeRequired()).isTrue();
        assertThat(result.confirmed()).isFalse();
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void codeRequiredCurrentStateOverridesPastSubmittedLedgerReplay() {
        // A(ACCEPTABLE→SUBMITTED) 후 B(LOW_ACCURACY→CODE_REQUIRED) 재제출. 늦은 A 재전송은
        // 과거 ledger(A SUBMITTED)를 보고 WAITING_COUNTERPART 로 되돌아가지 않고 현재
        // verification(CODE_REQUIRED)로 수렴한다.
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest pastSubmittedLedger = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(pastSubmittedLedger));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);
        MeetingVerification codeRequired = new MeetingVerification(
                CARD_ID, PET_1, UUID.randomUUID(), null, null, null, null,
                NOW.minusSeconds(10), MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(codeRequired));

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(result.codeRequired()).isTrue();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.meetingId()).isNull();
        assertThat(result.verificationMethod()).isNull();
        assertThat(result.confirmedAt()).isNull();
        assertThat(result.distanceMeters()).isNull();
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        // 현재 CODE_REQUIRED 와 raw null 이 그대로 유지된다.
        assertThat(codeRequired.getStatus()).isEqualTo(MeetingVerificationStatus.CODE_REQUIRED);
        assertThat(codeRequired.getLatitude()).isNull();
    }

    @Test
    void expiredSameRequestReplaysAsExpired() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);
        MeetingVerification expired = new MeetingVerification(
                CARD_ID, PET_1, clientRequestId, null, null, null, null,
                NOW.minus(2, ChronoUnit.HOURS), MeetingVerificationStatus.EXPIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(expired));

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.EXPIRED);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.codeRequired()).isFalse();
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        // EXPIRED 는 부활하지 않고 raw scrub 은 유지된다.
        assertThat(expired.getStatus()).isEqualTo(MeetingVerificationStatus.EXPIRED);
        assertThat(expired.getLatitude()).isNull();
    }

    @Test
    void expiredCounterpartDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MeetingVerification expiredCounterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), null, null, null, null,
                NOW.minus(2, ChronoUnit.HOURS), MeetingVerificationStatus.EXPIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(expiredCounterpart));

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.confirmed()).isFalse();
        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void leftAtHidesSubmitAsChatRoomNotFound() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        doThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND))
                .when(chatQueryService).requireParticipant(ROOM_ID, PET_1);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        verifyNoInteractions(locationService, meetingVerificationRequestRepository,
                meetingVerificationRepository, meetingRepository);
    }

    // ── 양쪽 GPS 확정 ──────────────────────────────────────────────────────

    @Test
    void bothAcceptableConfirmGpsMeetingAndScrubRaw() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MeetingVerification counterpart = submitted(capturedAt, NOW.minusSeconds(5));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));
        when(locationService.distanceMeters(any(), any())).thenReturn(42.7);
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            ReflectionTestUtils.setField(meeting, "id", 61L);
            return meeting;
        });

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(result.confirmed()).isTrue();
        assertThat(result.codeRequired()).isFalse();
        assertThat(result.distanceMeters()).isEqualTo(42.7);
        assertThat(result.counterpartSubmitted()).isTrue();
        // 양쪽 raw scrub + ACCEPTED
        assertThat(counterpart.getStatus()).isEqualTo(MeetingVerificationStatus.ACCEPTED);
        assertThat(counterpart.getLatitude()).isNull();
    }

    @Test
    void intervalUsesSubmittedAtNotCapturedAt() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // capturedAt 은 가깝지만 서버 수신시각(submittedAt)이 6분 전인 counterpart
        MeetingVerification counterpart = submitted(capturedAt, NOW.minus(6, ChronoUnit.MINUTES));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        verify(meetingRepository, never()).saveAndFlush(any());
        verify(locationService, never()).distanceMeters(any(), any());
    }

    @Test
    void distanceExceededDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MeetingVerification counterpart = submitted(capturedAt, NOW.minusSeconds(5));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));
        when(locationService.distanceMeters(any(), any())).thenReturn(200.0);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_DISTANCE_EXCEEDED);

        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void counterpartCodeRequiredDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), null, null, null, null,
                NOW.minusSeconds(5), MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
        assertThat(result.confirmed()).isFalse();
        verify(meetingRepository, never()).saveAndFlush(any());
        verify(locationService, never()).distanceMeters(any(), any());
    }

    // ── 기존 Meeting 수렴 ──────────────────────────────────────────────────

    @Test
    void freshRequestIdAfterGpsConfirmConvergesAndRecordsLedger() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        Meeting existing = new Meeting(CARD_ID, MeetingVerificationMethod.GPS, NOW, 42.7);
        ReflectionTestUtils.setField(existing, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(existing));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(result.confirmed()).isTrue();
        assertThat(result.distanceMeters()).isEqualTo(42.7);
        verify(meetingVerificationRequestRepository).saveAndFlush(any(MeetingVerificationRequest.class));
        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
    }

    @Test
    void existingCodeMeetingThrowsAlreadyConfirmed() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        Meeting existing = new Meeting(CARD_ID, MeetingVerificationMethod.CODE, NOW, null);
        ReflectionTestUtils.setField(existing, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);
    }

    // ── 영구 idempotency (HMAC fingerprint ledger) ─────────────────────────

    @Test
    void sameClientRequestIdWithSamePayloadReplaysWithoutReassess() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void gpsReplayAfterDeadlineReturnsConfirmedWithoutWrites() {
        // deadline(meetAt + 1h) 경과 뒤 동일 UUID·동일 payload 재시도도 ledger replay 로 확정 응답.
        stubOpenCardWithRoomTypeAndMeetAt(RoomType.DIRECT, NOW.minus(2, ChronoUnit.HOURS));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        Meeting meeting = new Meeting(CARD_ID, MeetingVerificationMethod.GPS,
                NOW.minus(1, ChronoUnit.HOURS), 42.7);
        ReflectionTestUtils.setField(meeting, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(meeting));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(result.confirmed()).isTrue();
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void codeRequiredReplayAfterDeadlineReturnsCodeRequiredWithoutWrites() {
        // deadline 경과 뒤에도 CODE_REQUIRED ledger 는 codeRequired=true replay 로 수렴한다.
        stubOpenCardWithRoomTypeAndMeetAt(RoomType.DIRECT, NOW.minus(2, ChronoUnit.HOURS));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, command.latitude(), command.longitude(),
                        command.accuracyMeters(), command.capturedAt()),
                MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.codeRequired()).isTrue();
        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameClientRequestIdDifferentPayloadConflictsAfterDeadline() {
        // deadline 경과 여부와 무관하게 같은 UUID + 다른 payload 는 REQUEST_CONFLICT 다.
        stubOpenCardWithRoomTypeAndMeetAt(RoomType.DIRECT, NOW.minus(2, ChronoUnit.HOURS));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand original = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, original.latitude(), original.longitude(),
                        original.accuracyMeters(), original.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        MeetingVerificationSubmitCommand changed = new MeetingVerificationSubmitCommand(
                clientRequestId, 99.0, original.longitude(), original.accuracyMeters(),
                original.capturedAt());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verifyNoInteractions(locationService);
        verify(meetingVerificationRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameClientRequestIdWithDifferentPayloadConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand original = command(clientRequestId);
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, CARD_ID, PET_1,
                fingerprint.compute(CARD_ID, PET_1, original.latitude(), original.longitude(),
                        original.accuracyMeters(), original.capturedAt()),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        MeetingVerificationSubmitCommand changed = new MeetingVerificationSubmitCommand(
                clientRequestId, 99.0, original.longitude(), original.accuracyMeters(),
                original.capturedAt());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verifyNoInteractions(locationService);
        verify(meetingRepository, never()).findByMeetingCardId(anyLong());
    }

    @Test
    void sameClientRequestIdWithDifferentCardConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationRequest existing = new MeetingVerificationRequest(
                clientRequestId, OTHER_CARD_ID, PET_1, "some-fingerprint",
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRequestRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(clientRequestId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verifyNoInteractions(locationService);
    }

    // ── 전역 clientRequestId UNIQUE 경합 번역 ───────────────────────────────

    @Test
    void requestLedgerUniqueViolationIsTranslatedToConflict() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        DataIntegrityViolationException race = new DataIntegrityViolationException(
                "duplicate client request id",
                new ConstraintViolationException(
                        "duplicate", new SQLException(), "pk_meeting_verification_requests"));
        when(meetingVerificationRequestRepository.saveAndFlush(any(MeetingVerificationRequest.class)))
                .thenThrow(race);

        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
    }

    @Test
    void otherLedgerConstraintViolationIsRethrownUntranslated() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRequestRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "check violation",
                new ConstraintViolationException(
                        "check", new SQLException(), "ck_meeting_verification_request_status"));
        when(meetingVerificationRequestRepository.saveAndFlush(any(MeetingVerificationRequest.class)))
                .thenThrow(other);

        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 상태 조회 ──────────────────────────────────────────────────────────

    @Test
    void getStatusReportsWaitingAndCodeRequired() {
        stubProjectionStatus("CODE_REQUIRED", null, null, null, null, null);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.CODE_REQUIRED);
        assertThat(status.codeRequired()).isTrue();
        assertThat(status.confirmed()).isFalse();
        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isFalse();
    }

    @Test
    void blockedGetStatusIsHiddenAsChatRoomNotFound() {
        stubStatusCard();
        doThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND))
                .when(chatQueryService).requireParticipant(ROOM_ID, PET_1);

        assertThatThrownBy(() -> service.getStatus(USER_1, CARD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        verify(meetingVerificationRepository, never()).findMeetingStatus(anyLong(), anyLong());
    }

    @Test
    void getStatusExpiredMapsToExpired() {
        stubProjectionStatus("EXPIRED", null, null, null, null, null);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.EXPIRED);
        assertThat(status.confirmed()).isFalse();
    }

    @Test
    void getStatusNotSubmittedMapsToNotSubmitted() {
        stubProjectionStatus(null, null, null, null, null, null);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.NOT_SUBMITTED);
        assertThat(status.mySubmitted()).isFalse();
        assertThat(status.counterpartSubmitted()).isFalse();
    }

    @Test
    void getStatusWaitingCounterpartMapsWhenOnlyMineSubmitted() {
        stubProjectionStatus("SUBMITTED", null, null, null, null, null);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.WAITING_COUNTERPART);
        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isFalse();
    }

    @Test
    void getStatusConfirmedReportsCounterpartSubmitted() {
        stubProjectionStatus("ACCEPTED", "ACCEPTED", 61L, "GPS", NOW, 42.7);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(status.confirmed()).isTrue();
        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isTrue();
        assertThat(status.distanceMeters()).isEqualTo(42.7);
    }

    @Test
    void getStatusCodeConfirmedMapsToCodeConfirmed() {
        stubProjectionStatus("ACCEPTED", "ACCEPTED", 61L, "CODE", NOW, null);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.CODE_CONFIRMED);
        assertThat(status.confirmed()).isTrue();
        assertThat(status.distanceMeters()).isNull();
    }

    @Test
    void getStatusMeetingPresentEnforcesCounterpartSubmittedInvariant() {
        // projection 에 상대 상태가 null 로 읽혀도 Meeting 이 존재하면 counterpartSubmitted 를
        // 강제한다(불변식: Meeting 존재 → 양쪽 제출 확정).
        stubProjectionStatus("ACCEPTED", null, 61L, "GPS", NOW, 42.7);

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.status()).isEqualTo(MeetingVerificationApiStatus.GPS_CONFIRMED);
        assertThat(status.confirmed()).isTrue();
        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isTrue();
    }

    @Test
    void getStatusUsesSingleProjectionQueryOnly() {
        stubProjectionStatus("SUBMITTED", null, null, null, null, null);

        service.getStatus(USER_1, CARD_ID);

        verify(meetingVerificationRepository).findMeetingStatus(CARD_ID, PET_1);
        verify(meetingVerificationRepository, never()).findAllByMeetingCardId(anyLong());
        verify(meetingRepository, never()).findByMeetingCardId(anyLong());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubOpenCard() {
        stubOpenCardWithRoomType(RoomType.DIRECT);
    }

    private void stubOpenCardWithRoomType(RoomType roomType) {
        stubOpenCardWithRoomTypeAndMeetAt(roomType, NOW);
    }

    private void stubOpenCardWithRoomTypeAndMeetAt(RoomType roomType, Instant meetAt) {
        stubCardExistenceAndParticipants();
        doReturn(Optional.of(cardIdentity())).when(meetingCardRepository).findIdentityById(CARD_ID);
        lenient().when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(card(meetAt)));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room(roomType)));
    }

    private void stubCanceledCard() {
        stubCardExistenceAndParticipants();
        MeetingCard canceled = card(NOW);
        canceled.cancel(PET_2, NOW);
        doReturn(Optional.of(cardIdentity())).when(meetingCardRepository).findIdentityById(CARD_ID);
        lenient().when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(canceled));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(directRoom()));
    }

    private void stubStatusCard() {
        when(meetingCardRepository.findById(CARD_ID)).thenReturn(Optional.of(card(NOW)));
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(true);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(directRoom()));
    }

    private void stubProjectionStatus(String myStatus, String counterpartStatus, Long meetingId,
                                      String method, Instant confirmedAt, Double distanceMeters) {
        stubStatusCard();
        MeetingVerificationRepository.MeetingStatusProjection snapshot = projection(
                myStatus, counterpartStatus, meetingId, method, confirmedAt, distanceMeters);
        when(meetingVerificationRepository.findMeetingStatus(CARD_ID, PET_1))
                .thenReturn(Optional.of(snapshot));
    }

    private MeetingVerificationRepository.MeetingStatusProjection projection(
            String myStatus, String counterpartStatus, Long meetingId,
            String method, Instant confirmedAt, Double distanceMeters) {
        MeetingVerificationRepository.MeetingStatusProjection snapshot = org.mockito.Mockito.mock(
                MeetingVerificationRepository.MeetingStatusProjection.class);
        when(snapshot.getMyStatus()).thenReturn(myStatus);
        when(snapshot.getCounterpartStatus()).thenReturn(counterpartStatus);
        when(snapshot.getMeetingId()).thenReturn(meetingId);
        when(snapshot.getVerificationMethod()).thenReturn(method);
        when(snapshot.getConfirmedAtEpochMillis())
                .thenReturn(confirmedAt == null ? null : confirmedAt.toEpochMilli());
        when(snapshot.getDistanceMeters()).thenReturn(distanceMeters);
        return snapshot;
    }

    private void stubCardExistenceAndParticipants() {
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(true);
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
    }

    private MeetingCard card(Instant meetAt) {
        return new MeetingCard(ROOM_ID, PET_1, null, MeetingCardType.WALK, "중앙공원", meetAt);
    }

    private MeetingCardRepository.MeetingCardIdentity cardIdentity() {
        MeetingCardRepository.MeetingCardIdentity identity = org.mockito.Mockito.mock(
                MeetingCardRepository.MeetingCardIdentity.class);
        lenient().when(identity.getId()).thenReturn(CARD_ID);
        lenient().when(identity.getRoomId()).thenReturn(ROOM_ID);
        return identity;
    }

    private ChatRoom directRoom() {
        return room(RoomType.DIRECT);
    }

    private ChatRoom room(RoomType type) {
        return new ChatRoom(type, RoomStatus.ACTIVE, RoomOrigin.GREETING,
                PET_1, PET_2, null, null, null, null, null);
    }

    private InteractionPairContext lockedPair() {
        return new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null));
    }

    private MeetingVerification submitted(Instant capturedAt, Instant submittedAt) {
        return new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                submittedAt, MeetingVerificationStatus.SUBMITTED);
    }

    private LocationAssessment acceptable(Instant capturedAt) {
        return new LocationAssessment(
                new ValidatedLocation(37.5665, 126.978, 24.5, capturedAt),
                LocationAccuracyQuality.ACCEPTABLE);
    }

    private LocationAssessment lowAccuracy(Instant capturedAt) {
        return new LocationAssessment(
                new ValidatedLocation(37.5665, 126.978, 60.0, capturedAt),
                LocationAccuracyQuality.LOW_ACCURACY);
    }

    private MeetingVerificationSubmitCommand command(UUID clientRequestId) {
        return command(clientRequestId, 37.5665, 126.978, 24.5, NOW.minusSeconds(10));
    }

    private MeetingVerificationSubmitCommand command(
            UUID clientRequestId, double latitude, double longitude, double accuracyMeters,
            Instant capturedAt) {
        return new MeetingVerificationSubmitCommand(
                clientRequestId, latitude, longitude, accuracyMeters, capturedAt);
    }
}
