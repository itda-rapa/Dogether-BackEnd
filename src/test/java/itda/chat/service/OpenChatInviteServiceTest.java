package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.RoomStatus;
import itda.chat.dto.response.OpenChatInviteResponse;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.repository.FriendshipRepository;
import itda.notification.domain.Notification;
import itda.notification.repository.NotificationRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenChatInviteServiceTest {

    private static final long USER_ID = 1L;
    private static final long ROOM_ID = 11L;
    private static final long ACTOR_PET_ID = 20L;
    private static final long TARGET_PET_ID = 10L;

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatRoomParticipantRepository participantRepository;
    @Mock private FriendshipRepository friendshipRepository;
    @Mock private ActivePetQueryService activePetQueryService;
    @Mock private ChatAuthorizationCacheService chatAuthorizationCacheService;
    @Mock private NotificationRepository notificationRepository;

    private OpenChatInviteService service;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        service = new OpenChatInviteService(chatRoomRepository, participantRepository,
                friendshipRepository, activePetQueryService, chatAuthorizationCacheService,
                notificationRepository);
        room = org.mockito.Mockito.mock(ChatRoom.class);
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(
                new ActivePetContext(ACTOR_PET_ID, USER_ID, "actor#1", "actor", null, false));
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(room.isOpenChat()).thenReturn(true);
        when(room.getStatus()).thenReturn(RoomStatus.ACTIVE);
    }

    @Test
    void invitesFriendIntoPrivateRoom() {
        allowActorAndFriend();
        when(participantRepository.findByRoomIdAndPetId(ROOM_ID, TARGET_PET_ID))
                .thenReturn(Optional.empty());
        when(participantRepository.countByRoomIdAndLeftAtIsNull(ROOM_ID)).thenReturn(2L);
        when(room.getMaxParticipants()).thenReturn(4);

        OpenChatInviteResponse response = service.invite(USER_ID, ROOM_ID, TARGET_PET_ID);

        assertThat(response).isEqualTo(new OpenChatInviteResponse(
                ROOM_ID, TARGET_PET_ID, true, 3));
        verify(participantRepository).save(any(ChatRoomParticipant.class));
        verify(notificationRepository).save(any(Notification.class));
        verify(chatAuthorizationCacheService).addParticipant(ROOM_ID, TARGET_PET_ID);
    }

    @Test
    void returnsIdempotentSuccessWhenFriendAlreadyParticipatesEvenIfRoomIsFull() {
        ChatRoomParticipant existing = org.mockito.Mockito.mock(ChatRoomParticipant.class);
        allowActorAndFriend();
        when(participantRepository.findByRoomIdAndPetId(ROOM_ID, TARGET_PET_ID))
                .thenReturn(Optional.of(existing));
        when(existing.getLeftAt()).thenReturn(null);
        when(participantRepository.countByRoomIdAndLeftAtIsNull(ROOM_ID)).thenReturn(4L);

        OpenChatInviteResponse response = service.invite(USER_ID, ROOM_ID, TARGET_PET_ID);

        assertThat(response.joined()).isFalse();
        assertThat(response.activeParticipants()).isEqualTo(4);
        verify(participantRepository, never()).save(any());
        verify(existing, never()).rejoin();
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void rejectsInviteFromPetThatIsNotAnActiveParticipant() {
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                ROOM_ID, ACTOR_PET_ID)).thenReturn(false);

        assertBusinessError(() -> service.invite(USER_ID, ROOM_ID, TARGET_PET_ID),
                ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        verify(friendshipRepository, never()).existsByPetLowIdAndPetHighId(any(), any());
    }

    @Test
    void rejectsPetThatIsNotActorsFriend() {
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                ROOM_ID, ACTOR_PET_ID)).thenReturn(true);
        when(friendshipRepository.existsByPetLowIdAndPetHighId(
                TARGET_PET_ID, ACTOR_PET_ID)).thenReturn(false);

        assertBusinessError(() -> service.invite(USER_ID, ROOM_ID, TARGET_PET_ID),
                ErrorCode.FRIENDSHIP_NOT_FOUND);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void rejectsInviteWhenRoomCapacityIsReached() {
        allowActorAndFriend();
        when(participantRepository.findByRoomIdAndPetId(ROOM_ID, TARGET_PET_ID))
                .thenReturn(Optional.empty());
        when(participantRepository.countByRoomIdAndLeftAtIsNull(ROOM_ID)).thenReturn(4L);
        when(room.getMaxParticipants()).thenReturn(4);

        assertBusinessError(() -> service.invite(USER_ID, ROOM_ID, TARGET_PET_ID),
                ErrorCode.CHAT_ROOM_FULL);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void rejoinsFriendThatPreviouslyLeft() {
        ChatRoomParticipant existing = ChatRoomParticipant.join(room, TARGET_PET_ID);
        existing.leave(Instant.now());
        allowActorAndFriend();
        when(participantRepository.findByRoomIdAndPetId(ROOM_ID, TARGET_PET_ID))
                .thenReturn(Optional.of(existing));
        when(participantRepository.countByRoomIdAndLeftAtIsNull(ROOM_ID)).thenReturn(1L);
        when(room.getMaxParticipants()).thenReturn(2);

        OpenChatInviteResponse response = service.invite(USER_ID, ROOM_ID, TARGET_PET_ID);

        assertThat(response.joined()).isTrue();
        assertThat(existing.getLeftAt()).isNull();
        verify(notificationRepository).save(any(Notification.class));
        verify(participantRepository, never()).save(any());
    }

    private void allowActorAndFriend() {
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                ROOM_ID, ACTOR_PET_ID)).thenReturn(true);
        when(friendshipRepository.existsByPetLowIdAndPetHighId(
                TARGET_PET_ID, ACTOR_PET_ID)).thenReturn(true);
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
