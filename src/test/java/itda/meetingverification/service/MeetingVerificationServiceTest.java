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
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingverification.domain.MeetingVerification;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.dto.MeetingVerificationSubmitCommand;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.sql.SQLException;
import java.time.Instant;
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
    private InteractionPairLockService interactionPairLockService;

    private MeetingVerificationService service;
    private ActivePetContext actor;

    @BeforeEach
    void setUp() {
        service = new MeetingVerificationService(
                activePetQueryService,
                meetingCardRepository,
                meetingParticipantRepository,
                meetingVerificationRepository,
                interactionPairLockService);
        actor = new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false);
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(actor);
    }

    @Test
    void rejectsMissingCardBeforeAcquiringLock() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(interactionPairLockService);
        verifyNoInteractions(meetingVerificationRepository);
    }

    @Test
    void rejectsNonParticipantBeforeAcquiringLock() {
        when(meetingCardRepository.existsById(CARD_ID)).thenReturn(true);
        when(meetingParticipantRepository.existsByMeetingCardIdAndPetId(CARD_ID, PET_1))
                .thenReturn(false);

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_NOT_PARTICIPANT);

        verifyNoInteractions(interactionPairLockService);
        verifyNoInteractions(meetingVerificationRepository);
    }

    @Test
    void locksPairThenReadsCardThenWritesVerificationWithoutMeeting() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
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

        InOrder order = inOrder(
                interactionPairLockService,
                meetingCardRepository,
                meetingVerificationRepository);
        order.verify(interactionPairLockService).lockInteractionPair(PET_1, PET_2);
        order.verify(meetingCardRepository).findByIdForUpdate(CARD_ID);
        order.verify(meetingVerificationRepository).saveAndFlush(any(MeetingVerification.class));
    }

    // ── clientRequestId 멱등·충돌 정책 ─────────────────────────────────────

    @Test
    void sameClientRequestIdWithSameCardPetAndPayloadIsIdempotent() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerificationSubmitCommand command = command(clientRequestId);
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, clientRequestId,
                command.latitude(), command.longitude(),
                command.accuracyMeters(), command.capturedAt());
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(false);

        MeetingVerificationResult first = service.submit(USER_1, CARD_ID, command);
        MeetingVerificationResult second = service.submit(USER_1, CARD_ID, command);

        assertThat(first.cardId()).isEqualTo(second.cardId());
        assertThat(first.submittedPetId()).isEqualTo(second.submittedPetId());
        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
        verify(meetingVerificationRepository, never())
                .findByMeetingCardIdAndParticipantPetId(anyLong(), anyLong());
    }

    @Test
    void sameClientRequestIdWithDifferentCardConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerification existing = new MeetingVerification(
                OTHER_CARD_ID, PET_1, clientRequestId, 1.0, 2.0, 5.0, NOW);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(clientRequestId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
    }

    @Test
    void sameClientRequestIdWithDifferentPetConflicts() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID clientRequestId = UUID.randomUUID();
        MeetingVerification otherPets = new MeetingVerification(
                CARD_ID, PET_2, clientRequestId, 1.0, 2.0, 5.0, NOW);
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(otherPets));

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(clientRequestId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
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
                original.accuracyMeters(), original.capturedAt());
        when(meetingVerificationRepository.findByClientRequestId(clientRequestId))
                .thenReturn(Optional.of(existing));

        // 같은 clientRequestId, 같은 카드/Pet 이지만 latitude 가 다르다.
        MeetingVerificationSubmitCommand changed = new MeetingVerificationSubmitCommand(
                clientRequestId, 99.0, original.longitude(),
                original.accuracyMeters(), original.capturedAt());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_VERIFICATION_REQUEST_CONFLICT);

        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
    }

    @Test
    void newClientRequestIdFromSamePetReplacesExistingSubmission() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        UUID oldRequestId = UUID.randomUUID();
        MeetingVerification existing = new MeetingVerification(
                CARD_ID, PET_1, oldRequestId, 1.0, 2.0, 5.0, NOW);
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.of(existing));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        UUID newRequestId = UUID.randomUUID();
        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(newRequestId, 9.0, 8.0, 3.0));

        assertThat(result.counterpartSubmitted()).isTrue();
        assertThat(existing.getClientRequestId()).isEqualTo(newRequestId);
        assertThat(existing.getLatitude()).isEqualTo(9.0);
        assertThat(existing.getLongitude()).isEqualTo(8.0);
        assertThat(existing.getAccuracyMeters()).isEqualTo(3.0);
        assertThat(existing.getStatus()).isEqualTo(MeetingVerificationStatus.SUBMITTED);
        verify(meetingVerificationRepository, never()).saveAndFlush(any(MeetingVerification.class));
    }

    // ── 상태·권한 ──────────────────────────────────────────────────────────

    @Test
    void rejectsCanceledCardAfterLock() {
        stubCanceledCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());

        assertThatThrownBy(() -> service.submit(USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_OPEN);

        verifyNoInteractions(meetingVerificationRepository);
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
    }

    @Test
    void reportsCounterpartSubmittedWhenCounterpartAlreadySubmitted() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingVerificationRepository.existsByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_2)).thenReturn(true);

        MeetingVerificationResult result = service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID()));

        assertThat(result.counterpartSubmitted()).isTrue();
    }

    // ── 전역 clientRequestId UNIQUE 경합 번역 ───────────────────────────────

    @Test
    void clientRequestUniqueViolationFromInsertRaceIsTranslatedToConflict() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
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
    void otherConstraintViolationIsRethrownUntranslated() {
        stubOpenCard();
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        when(meetingVerificationRepository.findByClientRequestId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(meetingVerificationRepository.findByMeetingCardIdAndParticipantPetId(
                CARD_ID, PET_1)).thenReturn(Optional.empty());
        DataIntegrityViolationException petSlotRace = new DataIntegrityViolationException(
                "duplicate pet slot",
                new ConstraintViolationException(
                        "duplicate", new SQLException(), "uk_meeting_verification_pet"));
        when(meetingVerificationRepository.saveAndFlush(any(MeetingVerification.class)))
                .thenThrow(petSlotRace);

        // uk_meeting_verification_pet 등 다른 제약 위반은 번역하지 않고 그대로 흘려보낸다.
        assertThatThrownBy(() -> service.submit(
                USER_1, CARD_ID, command(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    /** existsById·참여자 검사까지의 stub. findByIdForUpdate 는 잠금 뒤에 읽는 경로에서만 쓴다. */
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

    private MeetingVerificationSubmitCommand command(UUID clientRequestId) {
        return command(clientRequestId, 37.5665, 126.978, 24.5);
    }

    private MeetingVerificationSubmitCommand command(
            UUID clientRequestId, double latitude, double longitude, double accuracyMeters) {
        return new MeetingVerificationSubmitCommand(
                clientRequestId, latitude, longitude, accuracyMeters, NOW.minusSeconds(10));
    }
}
