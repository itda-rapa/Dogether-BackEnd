package itda.meetingverification.service;

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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
class MeetingVerificationServiceTest {

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long CARD_ID = 100L;
    private static final long OTHER_CARD_ID = 200L;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private MeetingCardRepository meetingCardRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private MeetingVerificationRepository meetingVerificationRepository;
    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private LocationService locationService;

    private MeetingVerificationService service;
    private ActivePetContext actor;

    @BeforeEach
    void setUp() {
        MeetingVerificationProperties properties =
                new MeetingVerificationProperties(100, Duration.ofMinutes(5));
        service = new MeetingVerificationService(
                activePetQueryService,
                meetingCardRepository,
                meetingParticipantRepository,
                meetingVerificationRepository,
                meetingRepository,
                interactionPairLockService,
                locationService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        actor = new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false);
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(actor);
    }

    // ── 권한·상태 오류는 Location 평가보다 먼저 ─────────────────────────────

    @Test
    void rejectsMissingCardBeforeLocationAssessment() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(locationService);
        verifyNoInteractions(interactionPairLockService);
    }

    @Test
    void rejectsNonParticipantBeforeLocationAssessment() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(true);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_PARTICIPANT);

        verifyNoInteractions(locationService);
        verifyNoInteractions(interactionPairLockService);
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
    void rejectsLockedPairMismatch() {
        stubCardExistenceAndParticipants();
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

        verify(meetingCardRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(locationService);
    }

    @Test
    void rejectsInactiveLockedStates() {
        stubCardExistenceAndParticipants();
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

        verify(meetingCardRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(locationService);
    }

    // ── Location 결과 처리 ─────────────────────────────────────────────────

    @Test
    void invalidLocationIsReturnedAfterAuthorization() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any()))
                .thenThrow(new BusinessException(ErrorCode.LOCATION_INVALID));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOCATION_INVALID);

        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void lowAccuracyStoresCodeRequiredWithoutMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any()))
                .thenReturn(lowAccuracy(NOW.minusSeconds(10)));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.confirmed()).isFalse();
        assertThat(result.meetingId()).isNull();
        assertThat(result.verificationMethod()).isNull();
        assertThat(result.confirmedAt()).isNull();
        assertThat(result.counterpartSubmitted()).isFalse();
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void firstAcceptableSubmitReturnsWaitingWithoutMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.cardId()).isEqualTo(CARD_ID);
        assertThat(result.submittedPetId()).isEqualTo(PET_1);
        assertThat(result.counterpartSubmitted()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.meetingId()).isNull();
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    // ── 양쪽 GPS 확정 ──────────────────────────────────────────────────────

    @Test
    void bothAcceptableWithinPolicyCreatesGpsMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                MeetingVerificationStatus.SUBMITTED);
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

        assertThat(result.confirmed()).isTrue();
        assertThat(result.meetingId()).isEqualTo(61L);
        assertThat(result.verificationMethod()).isEqualTo(MeetingVerificationMethod.GPS);
        assertThat(result.confirmedAt()).isEqualTo(NOW);
        assertThat(result.counterpartSubmitted()).isTrue();
    }

    @Test
    void distanceExceededDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                MeetingVerificationStatus.SUBMITTED);
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
    void intervalExceededDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant mineCapturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(mineCapturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0,
                NOW.minus(10, java.time.temporal.ChronoUnit.MINUTES),
                MeetingVerificationStatus.SUBMITTED);
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
    void counterpartCodeRequiredDoesNotCreateMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                MeetingVerificationStatus.CODE_REQUIRED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.confirmed()).isFalse();
        assertThat(result.meetingId()).isNull();
        assertThat(result.counterpartSubmitted()).isTrue();
        verify(meetingRepository, never()).saveAndFlush(any());
        verify(locationService, never()).distanceMeters(any(), any());
    }

    // ── 기존 Meeting 수렴 ──────────────────────────────────────────────────

    @Test
    void existingGpsMeetingConvergesIdempotently() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        Meeting existing = new Meeting(CARD_ID, MeetingVerificationMethod.GPS, NOW);
        ReflectionTestUtils.setField(existing, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(existing));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.confirmed()).isTrue();
        assertThat(result.meetingId()).isEqualTo(61L);
        assertThat(result.verificationMethod()).isEqualTo(MeetingVerificationMethod.GPS);
        verifyNoInteractions(locationService);
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void existingNonGpsMeetingThrowsAlreadyConfirmed() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        Meeting existing = new Meeting(CARD_ID, MeetingVerificationMethod.CODE, NOW);
        ReflectionTestUtils.setField(existing, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);

        verifyNoInteractions(locationService);
    }

    @Test
    void ukMeetingCardViolationConvergesToExistingGpsMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));
        when(locationService.distanceMeters(any(), any())).thenReturn(42.7);

        DataIntegrityViolationException race = new DataIntegrityViolationException(
                "duplicate meeting card",
                new ConstraintViolationException("duplicate", new SQLException(), "uk_meeting_card"));
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenThrow(race);
        Meeting winner = new Meeting(CARD_ID, MeetingVerificationMethod.GPS, NOW);
        ReflectionTestUtils.setField(winner, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID))
                .thenReturn(Optional.empty(), Optional.of(winner));

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.confirmed()).isTrue();
        assertThat(result.meetingId()).isEqualTo(61L);
    }

    @Test
    void meetingSaveOtherConstraintViolationIsNotHidden() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        Instant capturedAt = NOW.minusSeconds(10);
        when(locationService.assess(any())).thenReturn(acceptable(capturedAt));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);
        MeetingVerification counterpart = new MeetingVerification(
                CARD_ID, PET_2, UUID.randomUUID(), 37.5665, 126.978, 20.0, capturedAt,
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(Optional.of(counterpart));
        when(locationService.distanceMeters(any(), any())).thenReturn(42.7);
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "not null violation",
                new ConstraintViolationException("not null", new SQLException(),
                        "meetings_confirmed_at_not_null"));
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenThrow(other);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── clientRequestId 정합성 유지 ────────────────────────────────────────

    @Test
    void sameClientRequestIdWithSameCardPetAndPayloadIsIdempotent() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, clientRequestId,
                command.latitude(), command.longitude(),
                command.accuracyMeters(), command.capturedAt(),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult first = service.submit(USER_1, CARD_ID, command);
        MeetingVerificationResult second = service.submit(USER_1, CARD_ID, command);

        assertThat(first.cardId()).isEqualTo(second.cardId());
        assertThat(first.submittedPetId()).isEqualTo(second.submittedPetId());
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(locationService);
    }

    @Test
    void sameClientRequestIdWithDifferentCardConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerification existing = new MeetingVerification(
                OTHER_CARD_ID, PET_1, clientRequestId, 1.0, 2.0, 5.0, NOW,
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(clientRequestId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        // 이미 GPS Meeting 이 있더라도 충돌은 Meeting 조회보다 먼저 판정한다.
        verify(meetingRepository, never()).findByMeetingCardId(anyLong());
    }

    @Test
    void sameClientRequestIdWithDifferentPetConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerification otherPets = new MeetingVerification(
                CARD_ID, PET_2, clientRequestId, 1.0, 2.0, 5.0, NOW,
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(otherPets));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(clientRequestId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verify(meetingRepository, never()).findByMeetingCardId(anyLong());
    }

    @Test
    void sameClientRequestIdWithDifferentPayloadConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand original = command(clientRequestId);
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, clientRequestId,
                original.latitude(), original.longitude(),
                original.accuracyMeters(), original.capturedAt(),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        MeetingVerificationSubmitCommand changed = new MeetingVerificationSubmitCommand(
                clientRequestId, 99.0, original.longitude(),
                original.accuracyMeters(), original.capturedAt());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verify(meetingRepository, never()).findByMeetingCardId(anyLong());
    }

    @Test
    void existingGpsMeetingWithSameClientRequestIdSamePayloadConverges() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, clientRequestId,
                command.latitude(), command.longitude(),
                command.accuracyMeters(), command.capturedAt(),
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        Meeting meeting = new Meeting(CARD_ID, MeetingVerificationMethod.GPS, NOW);
        ReflectionTestUtils.setField(meeting, "id", 61L);
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.of(meeting));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        MeetingVerificationResult result = service.submit(USER_1, CARD_ID, command);

        assertThat(result.confirmed()).isTrue();
        assertThat(result.meetingId()).isEqualTo(61L);
        assertThat(result.verificationMethod()).isEqualTo(MeetingVerificationMethod.GPS);
        assertThat(result.confirmedAt()).isEqualTo(NOW);
        verifyNoInteractions(locationService);
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void newClientRequestIdFromSamePetReplacesExistingSubmission() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        UUID oldRequestId = UUID.randomUUID();
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, oldRequestId, 1.0, 2.0, 5.0, NOW,
                MeetingVerificationStatus.SUBMITTED);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(existing));
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        UUID newRequestId = UUID.randomUUID();
        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(newRequestId, 9.0, 8.0, 3.0));

        assertThat(result.confirmed()).isFalse();
        assertThat(existing.getClientRequestId()).isEqualTo(newRequestId);
        assertThat(existing.getLatitude()).isEqualTo(9.0);
        assertThat(existing.getStatus()).isEqualTo(MeetingVerificationStatus.SUBMITTED);
        verify(meetingVerificationRepository, never()).saveAndFlush(any());
    }

    // ── 전역 clientRequestId UNIQUE 경합 번역 ───────────────────────────────

    @Test
    void clientRequestUniqueViolationFromInsertRaceIsTranslatedToConflict() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        DataIntegrityViolationException race = new DataIntegrityViolationException(
                "duplicate client request id",
                new ConstraintViolationException(
                        "duplicate", new SQLException(), "uk_meeting_verification_client_request"));
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenThrow(race);

        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);
    }

    @Test
    void otherVerificationConstraintViolationIsRethrownUntranslated() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(locationService.assess(any())).thenReturn(acceptable(NOW.minusSeconds(10)));
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        DataIntegrityViolationException petSlotRace = new DataIntegrityViolationException(
                "duplicate pet slot",
                new ConstraintViolationException(
                        "duplicate", new SQLException(), "uk_meeting_verification_pet"));
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenThrow(petSlotRace);

        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 상태 조회 ──────────────────────────────────────────────────────────

    @Test
    void getStatusReportsMySubmissionAndCodeRequired() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(true);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(true);
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(new MeetingVerification(
                CARD_ID, PET_1, UUID.randomUUID(), 37.5665, 126.978, 60.0, NOW,
                MeetingVerificationStatus.CODE_REQUIRED)));
        when(meetingRepository.findByMeetingCardId(CARD_ID)).thenReturn(Optional.empty());

        MeetingVerificationStatusResponse status = service.getStatus(USER_1, CARD_ID);

        assertThat(status.mySubmitted()).isTrue();
        assertThat(status.counterpartSubmitted()).isFalse();
        assertThat(status.codeRequired()).isTrue();
        assertThat(status.confirmed()).isFalse();
        assertThat(status.meetingId()).isNull();
    }

    @Test
    void getStatusRejectsNonParticipant() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(true);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getStatus(USER_1, CARD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_PARTICIPANT);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubOpenCard() {
        stubCardExistenceAndParticipants();
        MeetingCard card = new MeetingCard(
                CARD_ID, PET_1, null, MeetingCardType.WALK, "중앙공원", NOW.plusSeconds(3600));
        when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(card));
    }

    private void stubCanceledCard() {
        stubCardExistenceAndParticipants();
        MeetingCard card = new MeetingCard(
                CARD_ID, PET_1, null, MeetingCardType.WALK, "중앙공원", NOW.plusSeconds(3600));
        card.cancel(PET_2, NOW);
        when(meetingCardRepository.findByIdForUpdate(CARD_ID)).thenReturn(Optional.of(card));
    }

    private void stubCardExistenceAndParticipants() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(true);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(true);
        when(meetingParticipantRepository.findPetIdsByMeetingCardId(CARD_ID))
                .thenReturn(List.of(PET_1, PET_2));
    }

    private InteractionPairContext lockedPair() {
        return new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null));
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
        return command(clientRequestId, 37.5665, 126.978, 24.5);
    }

    private MeetingVerificationSubmitCommand command(
            UUID clientRequestId, double latitude, double longitude, double accuracyMeters) {
        return new MeetingVerificationSubmitCommand(
                clientRequestId, latitude, longitude, accuracyMeters, NOW.minusSeconds(10));
    }
}
