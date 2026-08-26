package itda.meetingverification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingverification.MeetingVerificationProperties;
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.domain.MeetingConfirmationCode;
import itda.meetingverification.domain.MeetingVerification;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeetingConfirmationCodeServiceTest {

    private static final long CARD = 100L;
    private static final long ROOM = 500L;
    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final String HMAC_SECRET =
            "test-meeting-verification-hmac-secret-at-least-32-bytes";

    @Mock private ActivePetQueryService activePetQueryService;
    @Mock private MeetingCardRepository cardRepository;
    @Mock private MeetingParticipantRepository participantRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private MeetingVerificationRepository verificationRepository;
    @Mock private MeetingConfirmationCodeRepository codeRepository;
    @Mock private MeetingRepository meetingRepository;
    @Mock private InteractionPairLockService pairLockService;
    @Mock private ChatQueryService chatQueryService;
    @Mock private Clock clock;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final AtomicReference<MeetingConfirmationCode> storedCode = new AtomicReference<>();
    private MeetingConfirmationCodeService service;

    @BeforeEach
    void setUp() {
        MeetingVerificationProperties meetingProperties = new MeetingVerificationProperties(
                100.0, Duration.ofMinutes(5), Duration.ofHours(1), HMAC_SECRET,
                new MeetingVerificationProperties.Expiry(true, Duration.ofSeconds(60), 50));
        when(clock.instant()).thenReturn(NOW);
        service = new MeetingConfirmationCodeService(activePetQueryService, cardRepository,
                participantRepository, chatRoomRepository, verificationRepository, codeRepository,
                meetingRepository, pairLockService, chatQueryService, passwordEncoder,
                new ConfirmationCodeProperties(Duration.ofMinutes(5), 2),
                meetingProperties, clock);

        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(active(PET_1, USER_1));
        when(activePetQueryService.requireActivePet(USER_2)).thenReturn(active(PET_2, USER_2));
        lenient().when(cardRepository.findIdentityById(CARD)).thenReturn(Optional.of(cardIdentity()));
        when(participantRepository.existsByMeetingCardIdAndPetId(anyLong(), anyLong())).thenReturn(true);
        when(participantRepository.findPetIdsByMeetingCardId(CARD)).thenReturn(List.of(PET_1, PET_2));
        lenient().when(chatRoomRepository.findById(ROOM)).thenReturn(Optional.of(directRoom()));
        when(cardRepository.findByIdForUpdate(CARD)).thenReturn(Optional.of(card(NOW.plus(Duration.ofHours(1)))));
        when(pairLockService.lockInteractionPair(anyLong(), anyLong())).thenAnswer(invocation -> {
            long source = invocation.getArgument(0);
            long target = invocation.getArgument(1);
            return pair(source, target);
        });
        when(verificationRepository.findAllByMeetingCardId(CARD)).thenReturn(List.of(lowAccuracy()));
        when(meetingRepository.findByMeetingCardId(CARD)).thenReturn(Optional.empty());
        when(codeRepository.findByMeetingCardIdForUpdate(CARD)).thenAnswer(
                ignored -> Optional.ofNullable(storedCode.get()));
        when(codeRepository.save(any(MeetingConfirmationCode.class))).thenAnswer(invocation -> {
            MeetingConfirmationCode code = invocation.getArgument(0);
            storedCode.set(code);
            return code;
        });
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(
                invocation -> invocation.getArgument(0));
    }

    // ── 수정 1: #148과 동일한 접근 은닉 ─────────────────────────────────────

    @Test
    void issueUsesAuthorizationChatPairCardMeetingCodeThenMutationOrder() {
        service.issue(USER_1, CARD);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                activePetQueryService, chatQueryService, pairLockService, cardRepository,
                meetingRepository, verificationRepository, codeRepository);
        order.verify(activePetQueryService).requireActivePet(USER_1);
        order.verify(chatQueryService).requireParticipant(ROOM, PET_1);
        order.verify(pairLockService).lockInteractionPair(PET_1, PET_2);
        order.verify(cardRepository).findByIdForUpdate(CARD);
        order.verify(meetingRepository).findByMeetingCardId(CARD);
        order.verify(verificationRepository).findAllByMeetingCardId(CARD);
        order.verify(codeRepository).findByMeetingCardIdForUpdate(CARD);
        order.verify(codeRepository).save(any(MeetingConfirmationCode.class));
    }

    @Test
    void hidesNonParticipantAsNotFound() {
        when(participantRepository.existsByMeetingCardIdAndPetId(anyLong(), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(pairLockService);
    }

    @Test
    void hidesNonDirectCardAsNotFound() {
        lenient().when(chatRoomRepository.findById(ROOM)).thenReturn(Optional.of(room(RoomType.GROUP)));

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CARD_NOT_FOUND);

        verifyNoInteractions(pairLockService);
    }

    // ── 기존 회귀 ─────────────────────────────────────────────────────────

    @Test
    void onlyPersistedLowAccuracyEnablesIssuanceAndPlaintextIsNotPersisted() {
        ConfirmationCodeCreateResult result = service.issue(USER_1, CARD);

        assertThat(result.code()).matches("\\d{4}");
        assertThat(storedCode.get().getCodeHash()).isNotEqualTo(result.code());
        assertThat(passwordEncoder.matches(result.code(), storedCode.get().getCodeHash())).isTrue();
        assertThat(storedCode.get().getIssuerConfirmedAt()).isNull();

        when(verificationRepository.findAllByMeetingCardId(CARD)).thenReturn(List.of(normalAccuracy()));
        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_REQUIRED);
    }

    @Test
    void verifierThenIssuerConfirmCreatesExactlyOneCodeMeeting() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);

        ConfirmationCodeResult verified = service.verify(USER_2, CARD, issued.code());
        assertThat(verified.meetingId()).isNull();
        assertThat(storedCode.get().getVerifierPetId()).isEqualTo(PET_2);
        assertThat(storedCode.get().getIssuerConfirmedAt()).isNull();

        ConfirmationCodeResult confirmed = service.confirm(USER_1, CARD);
        assertThat(confirmed.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(storedCode.get().getIssuerConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void issuerCannotVerifyAndWrongAttemptsInvalidateTheCode() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        assertThatThrownBy(() -> service.verify(USER_1, CARD, issued.code()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_ISSUER_FORBIDDEN);

        assertThatThrownBy(() -> service.verify(USER_2, CARD, "0000"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_MISMATCH);
        assertThatThrownBy(() -> service.verify(USER_2, CARD, "0000"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_ATTEMPTS_EXCEEDED);
        assertThat(storedCode.get().getInvalidatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.verify(USER_2, CARD, issued.code()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_NOT_AVAILABLE);
    }

    @Test
    void verifyAndConfirmOnExistingCodeMeetingReturnConfirmed() {
        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofHours(2)))));
        when(meetingRepository.findByMeetingCardId(CARD)).thenReturn(
                Optional.of(new Meeting(CARD, MeetingVerificationMethod.CODE, NOW, null)));

        ConfirmationCodeResult verified = service.verify(USER_2, CARD, "1234");
        assertThat(verified.status()).isEqualTo("CONFIRMED");
        assertThat(verified.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);

        ConfirmationCodeResult confirmed = service.confirm(USER_1, CARD);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
    }

    @Test
    void verifyAndConfirmOnExistingGpsMeetingReturnAlreadyConfirmed() {
        when(meetingRepository.findByMeetingCardId(CARD)).thenReturn(
                Optional.of(new Meeting(CARD, MeetingVerificationMethod.GPS, NOW, 42.7)));

        assertThatThrownBy(() -> service.verify(USER_2, CARD, "1234"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);
        assertThatThrownBy(() -> service.confirm(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_ALREADY_CONFIRMED);
    }

    @Test
    void confirmAcceptsAndScrubsUnconfirmedVerifications() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        service.verify(USER_2, CARD, issued.code());

        service.confirm(USER_1, CARD);

        verify(verificationRepository).acceptUnconfirmedByCard(CARD);
    }

    @Test
    void effectiveExpiresAtIsCappedByMeetingDeadline() {
        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofMinutes(59)))));

        ConfirmationCodeCreateResult result = service.issue(USER_1, CARD);

        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void reissueAfterVerifierConfirmKeepsConflictForIssuerAndRejectsOtherPetAsForbidden() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        service.verify(USER_2, CARD, issued.code());

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_REISSUE_FORBIDDEN);
        assertThatThrownBy(() -> service.issue(USER_2, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN);
    }

    @Test
    void onlyExpiryAndAttemptStateExceptionIsNoRollbackEligible() throws Exception {
        Transactional transaction = MeetingConfirmationCodeService.class
                .getMethod("verify", Long.class, long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction.noRollbackFor()).containsExactly(ConfirmationCodeStatePersistException.class);
    }

    // ── 수정 2: 약속 시간창 deadline ────────────────────────────────────────

    @Test
    void pastDeadlineRejectsIssueVerifyConfirmWithoutMutatingCodeOrMeeting() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        String hashBefore = storedCode.get().getCodeHash();

        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofHours(2)))));

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThat(storedCode.get().getCodeHash()).isEqualTo(hashBefore);

        assertThatThrownBy(() -> service.verify(USER_2, CARD, issued.code()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThat(storedCode.get().getVerifierPetId()).isNull();

        assertThatThrownBy(() -> service.confirm(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThat(storedCode.get().getIssuerConfirmedAt()).isNull();

        verify(meetingRepository, never()).saveAndFlush(any(Meeting.class));
    }

    @Test
    void lockWaitCrossingDeadlineRejectsIssueWithoutCreatingCode() {
        // deadline = NOW. receivedAt(첫 호출) = deadline - 1ms, lock 뒤 now = deadline.
        // 이미 존재하는 code가 있어도 hash·상태를 바꾸지 않아야 한다.
        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofHours(1)))));
        MeetingConfirmationCode existing = new MeetingConfirmationCode(
                CARD, PET_1, "original-hash", NOW.plus(Duration.ofMinutes(5)));
        storedCode.set(existing);
        when(clock.instant()).thenReturn(
                NOW.minus(Duration.ofMillis(1)), NOW);

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(storedCode.get().getCodeHash()).isEqualTo("original-hash");
        assertThat(storedCode.get().getIssuerPetId()).isEqualTo(PET_1);
        verify(codeRepository, never()).save(any(MeetingConfirmationCode.class));
        verify(codeRepository).findByMeetingCardIdForUpdate(CARD);
    }

    @Test
    void exactDeadlineRejectsInitialIssueWithoutPersistingCode() {
        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofHours(1)))));

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(storedCode.get()).isNull();
        verify(codeRepository, never()).save(any(MeetingConfirmationCode.class));
    }

    @Test
    void exactDeadlineRejectsReissueVerifyAndConfirmWithoutAnyStateMutation() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        MeetingConfirmationCode code = storedCode.get();
        String hashBefore = code.getCodeHash();
        Instant expiresAtBefore = code.getExpiresAt();
        when(cardRepository.findByIdForUpdate(CARD))
                .thenReturn(Optional.of(card(NOW.minus(Duration.ofHours(1)))));

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThatThrownBy(() -> service.verify(USER_2, CARD, issued.code()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThatThrownBy(() -> service.confirm(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);

        assertThat(code.getIssuerPetId()).isEqualTo(PET_1);
        assertThat(code.getCodeHash()).isEqualTo(hashBefore);
        assertThat(code.getExpiresAt()).isEqualTo(expiresAtBefore);
        assertThat(code.getFailedAttempts()).isZero();
        assertThat(code.getVerifierPetId()).isNull();
        assertThat(code.getVerifierConfirmedAt()).isNull();
        assertThat(code.getIssuerConfirmedAt()).isNull();
        assertThat(code.getInvalidatedAt()).isNull();
        verify(meetingRepository, never()).saveAndFlush(any(Meeting.class));
        verify(verificationRepository, never()).acceptUnconfirmedByCard(anyLong());
    }

    @Test
    void instantBeforeDeadlineIssuesCodeWithStrictlyFutureExpiry() {
        when(cardRepository.findByIdForUpdate(CARD)).thenReturn(Optional.of(
                card(NOW.minus(Duration.ofHours(1)).plusNanos(1))));

        ConfirmationCodeCreateResult result = service.issue(USER_1, CARD);

        assertThat(result.expiresAt()).isEqualTo(NOW.plusNanos(1));
        assertThat(result.expiresAt()).isAfter(NOW);
        assertThat(storedCode.get().getExpiresAt()).isEqualTo(result.expiresAt());
    }

    @Test
    void reissueAllowedAfterVerifierConfirmAndTtlExpiryWithinDeadline() {
        AtomicReference<Instant> current = new AtomicReference<>(NOW);
        when(clock.instant()).thenAnswer(inv -> current.get());

        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        service.verify(USER_2, CARD, issued.code());

        // TTL(5m) 경과, deadline(meetAt + 1h = NOW + 2h) 이내.
        current.set(NOW.plus(Duration.ofMinutes(10)));

        ConfirmationCodeCreateResult reissued = service.issue(USER_1, CARD);

        assertThat(reissued.code()).matches("\\d{4}");
        assertThat(storedCode.get().getVerifierPetId()).isNull();
        assertThat(storedCode.get().getVerifierConfirmedAt()).isNull();
        assertThat(storedCode.get().getIssuerConfirmedAt()).isNull();
    }

    @Test
    void reissueRejectedAfterVerifierConfirmAndTtlExpiryPastDeadline() {
        AtomicReference<Instant> current = new AtomicReference<>(NOW);
        when(clock.instant()).thenAnswer(inv -> current.get());

        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        service.verify(USER_2, CARD, issued.code());

        // TTL·deadline 모두 경과.
        current.set(NOW.plus(Duration.ofHours(3)));
        String hashBefore = storedCode.get().getCodeHash();

        assertThatThrownBy(() -> service.issue(USER_1, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED);
        assertThat(storedCode.get().getCodeHash()).isEqualTo(hashBefore);
        assertThat(storedCode.get().getVerifierPetId()).isEqualTo(PET_2);
    }

    @Test
    void reissueAllowedAfterInvalidationWithinDeadline() {
        ConfirmationCodeCreateResult issued = service.issue(USER_1, CARD);
        String wrongCode = issued.code().equals("0000") ? "9999" : "0000";

        assertThatThrownBy(() -> service.verify(USER_2, CARD, wrongCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_MISMATCH);
        assertThatThrownBy(() -> service.verify(USER_2, CARD, wrongCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_ATTEMPTS_EXCEEDED);

        String invalidatedHash = storedCode.get().getCodeHash();
        int invalidatedAttempts = storedCode.get().getFailedAttempts();
        Instant invalidatedAt = storedCode.get().getInvalidatedAt();

        assertThatThrownBy(() -> service.issue(USER_2, CARD))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN);
        assertThat(storedCode.get().getIssuerPetId()).isEqualTo(PET_1);
        assertThat(storedCode.get().getCodeHash()).isEqualTo(invalidatedHash);
        assertThat(storedCode.get().getFailedAttempts()).isEqualTo(invalidatedAttempts);
        assertThat(storedCode.get().getInvalidatedAt()).isEqualTo(invalidatedAt);
        verify(meetingRepository, never()).saveAndFlush(any(Meeting.class));

        ConfirmationCodeCreateResult reissued = service.issue(USER_1, CARD);

        assertThat(reissued.code()).matches("\\d{4}");
        assertThat(storedCode.get().getIssuerPetId()).isEqualTo(PET_1);
        assertThat(storedCode.get().getFailedAttempts()).isZero();
        assertThat(storedCode.get().getInvalidatedAt()).isNull();
        assertThat(storedCode.get().getVerifierPetId()).isNull();
        assertThat(storedCode.get().getVerifierConfirmedAt()).isNull();
        assertThat(storedCode.get().getIssuerConfirmedAt()).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MeetingVerification lowAccuracy() {
        return new MeetingVerification(CARD, PET_1, UUID.randomUUID(), null, null, null, null,
                NOW, MeetingVerificationStatus.CODE_REQUIRED);
    }

    private MeetingVerification normalAccuracy() {
        return new MeetingVerification(CARD, PET_1, UUID.randomUUID(), 37.5, 127.0, 80.0, NOW,
                NOW, MeetingVerificationStatus.SUBMITTED);
    }

    private ActivePetContext active(long petId, long userId) {
        return new ActivePetContext(petId, userId, "pet#0001", "펫", null, false);
    }

    private InteractionPairContext pair(long sourcePet, long targetPet) {
        long sourceUser = sourcePet == PET_1 ? USER_1 : USER_2;
        long targetUser = targetPet == PET_1 ? USER_1 : USER_2;
        return new InteractionPairContext(
                new LockedUserContext(sourceUser, AccountStatus.ACTIVE, sourcePet, "user#1"),
                new LockedUserContext(targetUser, AccountStatus.ACTIVE, targetPet, "user#2"),
                new LockedPetContext(sourcePet, sourceUser, PetStatus.ACTIVE, null),
                new LockedPetContext(targetPet, targetUser, PetStatus.ACTIVE, null));
    }

    private MeetingCard card(Instant meetAt) {
        return new MeetingCard(ROOM, PET_1, null, MeetingCardType.WALK, "공원", meetAt);
    }

    private MeetingCardRepository.MeetingCardIdentity cardIdentity() {
        return new MeetingCardRepository.MeetingCardIdentity() {
            @Override
            public Long getId() {
                return CARD;
            }

            @Override
            public Long getRoomId() {
                return ROOM;
            }
        };
    }

    private ChatRoom directRoom() {
        return room(RoomType.DIRECT);
    }

    private ChatRoom room(RoomType type) {
        return new ChatRoom(type, RoomStatus.ACTIVE, RoomOrigin.GREETING,
                PET_1, PET_2, null, null, null, null, null);
    }
}
