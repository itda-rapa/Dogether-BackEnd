package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
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
import itda.meetingverification.domain.Meeting;
import itda.meetingverification.domain.MeetingConfirmationCode;
import itda.meetingverification.domain.MeetingVerification;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.domain.MeetingVerificationStatus;
import itda.meetingverification.dto.ConfirmationCodeResult;
import itda.meetingverification.repository.MeetingConfirmationCodeRepository;
import itda.meetingverification.repository.MeetingRepository;
import itda.meetingverification.repository.MeetingVerificationRepository;
import itda.meetingverification.service.MeetingConfirmationCodeService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class MeetingConfirmationCodeTransactionTest {

    private static final long CARD = 51L;
    private static final long ROOM = 500L;
    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired private MeetingConfirmationCodeService service;
    @Autowired private MeetingConfirmationCodeRepository codeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private ActivePetQueryService activePetQueryService;
    @MockitoBean private MeetingCardRepository cardRepository;
    @MockitoBean private MeetingParticipantRepository participantRepository;
    @MockitoBean private ChatRoomRepository chatRoomRepository;
    @MockitoBean private MeetingVerificationRepository verificationRepository;
    @MockitoBean private MeetingRepository meetingRepository;
    @MockitoBean private InteractionPairLockService pairLockService;
    @MockitoBean private ChatQueryService chatQueryService;

    @AfterEach
    void cleanUp() {
        codeRepository.deleteAll();
    }

    @Test
    void confirmCommitsIssuerConfirmationAndAcceptsUnconfirmedVerifications() {
        stubAuthorizedLowAccuracyAccess();
        MeetingConfirmationCode active = persistedCode(Instant.now().plusSeconds(300));
        active.verifyBy(PET_2, NOW);
        active = codeRepository.saveAndFlush(active);

        ConfirmationCodeResult result = service.confirm(USER_1, CARD);

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.verificationMethod()).isEqualTo(MeetingVerificationMethod.CODE);
        assertThat(codeRepository.findById(active.getId()).orElseThrow().getIssuerConfirmedAt())
                .isNotNull();
        verify(meetingRepository).saveAndFlush(any(Meeting.class));
        verify(verificationRepository).acceptUnconfirmedByCard(CARD);
    }

    @Test
    void expiredCodeInvalidationCommitsOnVerify() {
        stubAuthorizedLowAccuracyAccess();
        MeetingConfirmationCode expired = persistedCode(Instant.now().minusSeconds(1));
        expired = codeRepository.saveAndFlush(expired);

        assertThatThrownBy(() -> service.verify(USER_2, CARD, "1234"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.MEETING_CODE_EXPIRED);
        assertThat(codeRepository.findById(expired.getId()).orElseThrow().getInvalidatedAt())
                .isNotNull();
    }

    private MeetingConfirmationCode persistedCode(Instant expiresAt) {
        return codeRepository.saveAndFlush(new MeetingConfirmationCode(
                CARD, PET_1, passwordEncoder.encode("1234"), expiresAt));
    }

    private void stubAuthorizedLowAccuracyAccess() {
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(active(PET_1, USER_1));
        when(activePetQueryService.requireActivePet(USER_2)).thenReturn(active(PET_2, USER_2));
        when(cardRepository.findIdentityById(CARD)).thenReturn(Optional.of(cardIdentity()));
        when(participantRepository.existsByMeetingCardIdAndPetId(anyLong(), anyLong())).thenReturn(true);
        when(participantRepository.findPetIdsByMeetingCardId(CARD)).thenReturn(List.of(PET_1, PET_2));
        when(chatRoomRepository.findById(ROOM)).thenReturn(Optional.of(directRoom()));
        when(cardRepository.findByIdForUpdate(CARD)).thenReturn(Optional.of(card(Instant.now().plusSeconds(3600))));
        when(pairLockService.lockInteractionPair(anyLong(), anyLong())).thenAnswer(invocation -> {
            long sourcePet = invocation.getArgument(0);
            long targetPet = invocation.getArgument(1);
            return pair(sourcePet, targetPet);
        });
        MeetingVerification low = new MeetingVerification(
                CARD, PET_1, UUID.randomUUID(), null, null, null, null, NOW,
                MeetingVerificationStatus.CODE_REQUIRED);
        when(verificationRepository.findAllByMeetingCardId(CARD)).thenReturn(List.of(low));
        when(meetingRepository.findByMeetingCardId(CARD)).thenReturn(Optional.empty());
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(
                invocation -> invocation.getArgument(0));
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
        return new ChatRoom(RoomType.DIRECT, RoomStatus.ACTIVE, RoomOrigin.GREETING,
                PET_1, PET_2, null, null, null, null, null);
    }
}
