package itda.chat.service;

import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.response.ChatMessageListResponse;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.ChatRoomResponse;
import itda.chat.dto.response.CursorPage;
import itda.chat.dto.response.PetSearchItem;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.repository.ChatRoomRepository.RoomListRow;
import itda.chat.support.RoomCursorCodec;
import itda.chat.support.RoomCursorCodec.CursorPayload;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side orchestration for the chat REST polling API (M1-015).
 *
 * <p>All queries filter to rooms where the caller's active pet is a participant.
 * Missing room, non-participant, and future block relationships all produce
 * {@code CHAT_ROOM_NOT_FOUND} (404) — see §7.3 of the M1-015 work order.
 */
@Service
@RequiredArgsConstructor
public class ChatQueryService {

    private static final int DEFAULT_ROOM_LIMIT = 20;
    private static final int MAX_ROOM_LIMIT = 100;
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageService chatMessageService;
    private final GreetingRepository greetingRepository;
    private final ActivePetQueryService activePetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final ChatMessageEventPublisher chatMessageEventPublisher;

    // ── GET /chat/rooms ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatRoomListResult getRooms(long userId, String encodedCursor, Integer limit) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        int effectiveLimit;
        if (limit == null) {
            effectiveLimit = DEFAULT_ROOM_LIMIT;
        } else if (limit < 1 || limit > MAX_ROOM_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        } else {
            effectiveLimit = limit;
        }

        CursorPayload cursor = RoomCursorCodec.decode(encodedCursor);
        String cursorActivityAt = (cursor != null) ? cursor.activityAt() : null;
        long cursorRoomId = (cursor != null) ? cursor.roomId() : 0L;

        int limitPlusOne = effectiveLimit + 1;
        List<RoomListRow> rows = chatRoomRepository.findRoomsWithLastMessage(
                actor.petId(), cursorActivityAt, cursorRoomId, limitPlusOne);

        boolean hasNext = rows.size() > effectiveLimit;
        List<RoomListRow> page = hasNext
                ? rows.subList(0, effectiveLimit)
                : rows;

        // Collect counterpart pet ids — the other pet in each DIRECT room
        Set<Long> counterpartPetIds = page.stream()
                .map(r -> counterpartPetId(actor.petId(), r.getPetLowId(), r.getPetHighId()))
                .collect(Collectors.toSet());

        Map<Long, PetDisplaySummary> summaries = petDisplayQueryService
                .getPetDisplaySummaries(counterpartPetIds);

        List<ChatRoomResponse> items = new ArrayList<>(page.size());
        for (RoomListRow row : page) {
            items.add(toChatRoomResponse(row, actor, summaries));
        }

        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            RoomListRow last = page.get(page.size() - 1);
            java.time.Instant activityAt = last.getLastMessageAt() != null
                    ? last.getLastMessageAt()
                    : last.getCreatedAt();
            nextCursor = RoomCursorCodec.encode(last.getRoomId(), activityAt);
        }

        return new ChatRoomListResult(items, CursorPage.of(nextCursor, hasNext));
    }

    // ── GET /chat/rooms/{roomId} ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatRoomResponse getRoom(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        requireParticipant(roomId, actor.petId());

        // Fetch the single room by id, verifying the active pet is a participant
        RoomListRow row = chatRoomRepository.findRoomById(
                actor.petId(), roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        long counterpartId = counterpartPetId(actor.petId(), row.getPetLowId(), row.getPetHighId());
        Map<Long, PetDisplaySummary> summaries = petDisplayQueryService
                .getPetDisplaySummaries(Set.of(counterpartId));

        return toChatRoomResponse(row, actor, summaries);
    }

    /**
     * Checks that the active pet is a current participant of the room and has not
     * left (§7.3 existence hiding). Throws CHAT_ROOM_NOT_FOUND (404) — same response
     * whether the room does not exist, the active pet is not a participant, or
     * a future block relationship excludes it.
     */
    public void requireParticipant(long roomId, long petId) {
        if (!chatRoomRepository.existsAccessibleRoomForPet(roomId, petId)) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
    }

    public boolean isActiveParticipant(long roomId, long petId) {
        return chatRoomRepository.existsAccessibleRoomForPet(roomId, petId);
    }

    /**
     * Server-authored room interactions such as meeting cards are unavailable while the first
     * Greeting still waits for its recipient's TEXT reply. Unlike {@code sendText}, a card cannot
     * itself count as that reply.
     */
    public void requireGreetingReplyCompleted(long roomId) {
        if (greetingRepository.existsByRoomIdAndStatus(roomId, GreetingStatus.SENT)) {
            throw new BusinessException(ErrorCode.GREETING_REPLY_REQUIRED);
        }
    }

    // ── Helper: room response assembly ─────────────────────────────────────

    private ChatRoomResponse toChatRoomResponse(RoomListRow row,
                                                ActivePetContext actor,
                                                Map<Long, PetDisplaySummary> summaries) {
        long actorPetId = actor.petId();
        long counterpartId = counterpartPetId(actorPetId, row.getPetLowId(), row.getPetHighId());
        PetDisplaySummary summary = summaries.get(counterpartId);

        PetSearchItem counterpart = new PetSearchItem(
                summary.petId(),
                summary.publicTag(),
                summary.nickname(),
                summary.profileUrl(),
                summary.verified(),
                resolveRelationship(actorPetId, counterpartId));

        ChatMessageResponse lastMessage = row.getMsgId() != null
                ? new ChatMessageResponse(
                row.getMsgId(), row.getRoomId(), row.getMsgSenderType(),
                row.getMsgSenderPetId(), senderPetNickname(
                        row.getMsgSenderPetId(), actorPetId, summaries, actor.nickname()),
                row.getMsgType(), row.getMsgBody(),
                row.getMsgMeetingCardId(), row.getMsgClientMessageId(),
                row.getMsgCreatedAt())
                : null;

        return new ChatRoomResponse(
                row.getRoomId(),
                row.getStatus(),
                row.getOrigin(),
                counterpart,
                canSend(row.getStatus()),
                sendBlockedReason(row.getStatus()),
                lastMessage,
                row.getLastMessageAt(),
                row.getUpdatedAt());
    }

    // ── Helper: counterpart identification ──────────────────────────────────

    private long counterpartPetId(long activePetId, Long petLowId, Long petHighId) {
        return petLowId == activePetId ? petHighId : petLowId;
    }

    // ── M1-015 stubs ─────────────────────────────────────────────────────────

    /**
     * M1-015: no friend query contract yet. The OpenAPI schema permits a null
     * relationship, so emit null until a batch lookup keyed by
     * (activePetId, counterpartPetIds) lands. Single seam.
     */
    private String resolveRelationship(long activePetId, long counterpartPetId) {
        return null;
    }

    /**
     * Archived rooms remain readable and accept a message because the message write path
     * restores them to ACTIVE. Keep this status-aware rather than hiding the lifecycle state
     * behind an unconditional stub.
     */
    private boolean canSend(String roomStatus) {
        return "ACTIVE".equals(roomStatus) || "ARCHIVED".equals(roomStatus);
    }

    private String sendBlockedReason(String roomStatus) {
        return null;
    }

    // ── GET /chat/rooms/{roomId}/messages ────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatMessageListResult getMessages(long userId, long roomId, long afterMessageId, Integer limit) {
        return getMessagePage(userId, roomId, Long.valueOf(afterMessageId), limit);
    }

    @Transactional(readOnly = true)
    public ChatMessageListResult getMessagePage(long userId, long roomId, Long afterMessageId, Integer limit) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        requireParticipant(roomId, actor.petId());

        int effectiveLimit;
        if (limit == null) {
            effectiveLimit = DEFAULT_MESSAGE_LIMIT;
        } else if (limit < 1 || limit > MAX_MESSAGE_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        } else {
            effectiveLimit = limit;
        }

        int limitPlusOne = effectiveLimit + 1;
        var pageable = PageRequest.of(0, limitPlusOne);
        boolean initialLoad = afterMessageId == null;
        var messages = initialLoad
                ? chatMessageRepository.findLatestMessages(roomId, pageable)
                : chatMessageRepository.findMessagesAfter(roomId, afterMessageId, pageable);

        boolean hasMore = messages.size() > effectiveLimit;
        if (hasMore) {
            messages = messages.subList(0, effectiveLimit);
        }
        if (initialLoad) {
            messages = new ArrayList<>(messages);
            java.util.Collections.reverse(messages);
        }

        Set<Long> senderPetIds = messages.stream()
                .map(message -> message.getSenderPetId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PetDisplaySummary> senderPets = petDisplayQueryService
                .getPetDisplaySummaries(senderPetIds);

        List<ChatMessageResponse> items = messages.stream()
                .map(message -> ChatMessageResponse.from(
                        message,
                        senderPetNickname(message.getSenderPetId(), actor.petId(), senderPets, actor.nickname())))
                .collect(Collectors.toList());

        Long nextAfterMessageId;
        if (!items.isEmpty()) {
            nextAfterMessageId = items.get(items.size() - 1).messageId();
        } else {
            nextAfterMessageId = afterMessageId;
        }

        return new ChatMessageListResult(
                new ChatMessageListResponse(items, nextAfterMessageId, hasMore));
    }

    // ── POST /chat/rooms/{roomId}/messages ───────────────────────────────────

    /**
     * Send a text message and return its DTO.
     *
     * <p>Wraps the Core {@link ChatMessageService#sendText} call inside a
     * read-write transaction so the returned entity stays attached while we read
     * {@code message.getRoom().getId()} for {@link ChatMessageResponse#from}.
     * {@code requireParticipant} runs first — the controller must never reach
     * Core's own {@code CHAT_SENDER_NOT_PARTICIPANT} guard (§7.3 existence hiding).
     */
    @Transactional
    public SendMessageResult sendMessage(long userId, long roomId, ChatMessageCreateRequest request) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        requireParticipant(roomId, actor.petId());
        ChatMessageResult result = chatMessageService.sendText(roomId, actor.petId(), request);
        ChatMessageResponse dto = ChatMessageResponse.from(result.message(), actor.nickname());
        if (result.created()) {
            chatMessageEventPublisher.publishAfterCommit(dto);
        }
        return new SendMessageResult(dto, result.created());
    }

    // ── Result types ─────────────────────────────────────────────────────────

    public record ChatRoomListResult(List<ChatRoomResponse> items, CursorPage page) {
    }

    public record ChatMessageListResult(ChatMessageListResponse data) {
    }

    public record SendMessageResult(ChatMessageResponse data, boolean created) {
    }

    private String senderPetNickname(Long senderPetId,
                                     long actorPetId,
                                     Map<Long, PetDisplaySummary> senderPets,
                                     String actorNickname) {
        if (senderPetId == null) {
            return null;
        }
        if (senderPetId == actorPetId && actorNickname != null) {
            return actorNickname;
        }
        PetDisplaySummary sender = senderPets.get(senderPetId);
        return sender != null ? sender.nickname() : null;
    }
}
