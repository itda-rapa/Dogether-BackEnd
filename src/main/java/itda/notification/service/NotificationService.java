package itda.notification.service;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.notification.domain.Notification;
import itda.notification.dto.NotificationResponse;
import itda.notification.dto.NotificationUnreadCountResponse;
import itda.notification.repository.NotificationRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final ActivePetQueryService activePetQueryService;
    private final PetRepository petRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final NotificationTargetAvailabilityService targetAvailabilityService;

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(long userId) {
        ActivePetContext activePet = activePetQueryService.requireActivePet(userId);
        List<Notification> notifications = notificationRepository
                .findTop100ByTargetPetIdOrderByCreatedAtDescIdDesc(activePet.petId());
        Map<Long, Pet> actors = petRepository.findAllById(
                        notifications.stream().map(Notification::getActorPetId).filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Pet::getId, Function.identity()));
        Map<Long, ChatRoom> rooms = chatRoomRepository.findAllById(
                        notifications.stream().map(Notification::getRoomId).filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ChatRoom::getId, Function.identity()));
        return notifications.stream().map(n -> toResponse(n, activePet, actors, rooms)).toList();
    }

    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse unreadCount(long userId) {
        ActivePetContext activePet = activePetQueryService.requireActivePet(userId);
        return new NotificationUnreadCountResponse(
                notificationRepository.countByTargetPetIdAndReadAtIsNull(activePet.petId()));
    }

    @Transactional
    public NotificationResponse markRead(long userId, long notificationId) {
        ActivePetContext activePet = activePetQueryService.requireActivePet(userId);
        Notification notification = notificationRepository.findByIdAndTargetPetId(notificationId, activePet.petId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        notification.markRead(Instant.now());
        Pet actor = petRepository.findById(notification.getActorPetId()).orElse(null);
        ChatRoom room = notification.getRoomId() == null ? null
                : chatRoomRepository.findById(notification.getRoomId()).orElse(null);
        return toResponse(notification, activePet, actor == null ? Map.of() : Map.of(actor.getId(), actor),
                room == null ? Map.of() : Map.of(room.getId(), room));
    }

    private NotificationResponse toResponse(Notification notification, ActivePetContext recipient, Map<Long, Pet> actors,
            Map<Long, ChatRoom> rooms) {
        Pet actor = actors.get(notification.getActorPetId());
        ChatRoom room = rooms.get(notification.getRoomId());
        String nickname = notification.getActorPetNicknameSnapshot() != null
                ? notification.getActorPetNicknameSnapshot()
                : actor == null ? "친구" : actor.getNickname();
        Long profileAssetId = notification.getActorProfileAssetIdSnapshot() != null
                ? notification.getActorProfileAssetIdSnapshot()
                : actor == null || actor.getProfileAsset() == null ? null : actor.getProfileAsset().getId();
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getTargetType(),
                notification.getTargetId(), notification.getRoomId(), room == null ? "오픈채팅방" : room.getTitle(),
                notification.getPostId(), notification.getSetlogId(), notification.getActorPetId(), nickname,
                profileAssetId, notification.getCommentPreviewSnapshot(),
                targetAvailabilityService.isAvailable(notification, recipient, room),
                notification.getCreatedAt(), notification.getReadAt());
    }
}
