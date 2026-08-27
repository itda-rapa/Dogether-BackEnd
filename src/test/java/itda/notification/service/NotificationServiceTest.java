package itda.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.notification.domain.Notification;
import itda.notification.domain.NotificationType;
import itda.notification.domain.NotificationTargetType;
import itda.notification.repository.NotificationRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationRepository notificationRepository;
    @Mock private ActivePetQueryService activePetQueryService;
    @Mock private PetRepository petRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private NotificationTargetAvailabilityService targetAvailabilityService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, activePetQueryService,
                petRepository, chatRoomRepository, targetAvailabilityService);
        when(activePetQueryService.requireActivePet(1L)).thenReturn(
                new ActivePetContext(10L, 1L, "target#1", "target", null, false));
    }

    @Test
    void listsOnlyActivePetsNotificationsWithRoomAndInviterNames() {
        Notification notification = mock(Notification.class);
        Pet actor = mock(Pet.class);
        ChatRoom room = mock(ChatRoom.class);
        Instant createdAt = Instant.parse("2026-08-13T09:00:00Z");
        when(notification.getId()).thenReturn(7L);
        when(notification.getActorPetId()).thenReturn(20L);
        when(notification.getRoomId()).thenReturn(30L);
        when(notification.getType()).thenReturn(NotificationType.OPEN_CHAT_INVITE);
        when(notification.getTargetType()).thenReturn(NotificationTargetType.OPEN_CHAT_ROOM);
        when(notification.getCreatedAt()).thenReturn(createdAt);
        when(actor.getId()).thenReturn(20L);
        when(actor.getNickname()).thenReturn("초대견");
        when(room.getId()).thenReturn(30L);
        when(room.getTitle()).thenReturn("모란 산책방");
        when(notificationRepository.findTop100ByTargetPetIdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(notification));
        when(petRepository.findAllById(java.util.Set.of(20L))).thenReturn(List.of(actor));
        when(chatRoomRepository.findAllById(java.util.Set.of(30L))).thenReturn(List.of(room));
        when(targetAvailabilityService.resolveAll(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of(7L, true));

        var result = service.list(1L);

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.notificationId()).isEqualTo(7L);
            assertThat(response.roomId()).isEqualTo(30L);
            assertThat(response.roomTitle()).isEqualTo("모란 산책방");
            assertThat(response.actorPetNickname()).isEqualTo("초대견");
        });
    }

    @Test
    void cannotReadAnotherPetsNotification() {
        when(notificationRepository.findByIdAndTargetPetId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(notificationRepository, never()).findById(99L);
    }

    @Test
    void returnsUnreadCountForActivePet() {
        when(notificationRepository.countByTargetPetIdAndReadAtIsNull(10L)).thenReturn(4L);

        var result = service.unreadCount(1L);

        assertThat(result.unreadCount()).isEqualTo(4L);
        verify(notificationRepository).countByTargetPetIdAndReadAtIsNull(10L);
    }
}
