package itda.chat.service;

import itda.chat.domain.AttachmentType;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatMessageAttachment;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.repository.ChatMessageAttachmentRepository;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatMessageRepository.MessageUpsert;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.event.ChatMessageCommittedEvent;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.media.domain.MediaType;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.setlog.service.SetlogQueryService;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MAX_BODY_LENGTH = 2000;
    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 64;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageAttachmentRepository attachmentRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final GreetingRepository greetingRepository;
    private final PetRepository petRepository;
    private final MediaService mediaService;
    private final SetlogQueryService setlogQueryService;
    private final ChatMessageResponseAssembler responseAssembler;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 사용자 전송 메시지의 공통 진입점. TEXT는 기존 인사 답변 게이트를, IMAGE/VIDEO/SETLOG_SHARE는
     * 각각 media/setlog 공개 계약 검증을 거쳐 동일한 {@link #insert} 경로로 저장한다.
     * CARD/SYSTEM은 서버 전용이므로 외부 요청에서 거부한다.
     */
    @Transactional
    public ChatMessageResult sendMessage(long roomId, long senderPetId, long ownerUserId,
                                         ChatMessageCreateRequest request) {
        MessageType type = normalizeType(request);
        return switch (type) {
            case TEXT -> sendText(roomId, senderPetId, request);
            case IMAGE -> sendMedia(roomId, senderPetId, ownerUserId, request, AttachmentType.IMAGE);
            case VIDEO -> sendMedia(roomId, senderPetId, ownerUserId, request, AttachmentType.VIDEO);
            case SETLOG_SHARE -> sendSetlogShare(roomId, senderPetId, ownerUserId, request);
            default -> throw new BusinessException(ErrorCode.CHAT_MESSAGE_TYPE_INVALID);
        };
    }

    /**
     * M3 typed 계약의 하위 호환: {@code type}이 누락된 legacy TEXT 요청(mediaId/setlogId 없음)은
     * TEXT로 정규화한다. type 없이 mediaId/setlogId만 있으면 타입을 특정할 수 없으므로 거부한다.
     */
    private MessageType normalizeType(ChatMessageCreateRequest request) {
        MessageType type = request.type();
        if (type != null) {
            if (!type.isUserSettable()) {
                throw new BusinessException(ErrorCode.CHAT_MESSAGE_TYPE_INVALID);
            }
            return type;
        }
        if (request.mediaId() == null && request.setlogId() == null) {
            return MessageType.TEXT;
        }
        throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    /**
     * Send a user-authored TEXT message. M1 인사 답변 게이트는 TEXT에만 적용된다.
     */
    @Transactional
    public ChatMessageResult sendText(long roomId, long senderPetId, ChatMessageCreateRequest request) {
        requireClientMessageId(request);
        Optional<Greeting> greetingToRespond =
                greetingRepository
                        .findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                                roomId,
                                senderPetId,
                                GreetingStatus.SENT
                        );
        if (greetingToRespond.isEmpty()
                && greetingRepository
                .existsByRoomIdAndFromPet_IdAndStatus(
                        roomId,
                        senderPetId,
                        GreetingStatus.SENT
                )) {
            throw new BusinessException(ErrorCode.GREETING_REPLY_REQUIRED);
        }

        ChatMessageResult result = insert(roomId, SenderType.PET, senderPetId,
                MessageType.TEXT, request.body(), null, null, request.clientMessageId(),
                null, null, null);
        if (result.created()) {
            greetingToRespond.ifPresent(greeting ->
                    greeting.markResponded(Instant.now())
            );
        }
        return result;
    }

    /**
     * Stores the server-defined first Greeting text without applying the
     * "wait for reply" gate that immediately becomes active afterward.
     */
    @Transactional
    public ChatMessageResult sendGreetingText(
            long roomId,
            long senderPetId,
            ChatMessageCreateRequest request
    ) {
        return insert(
                roomId,
                SenderType.PET,
                senderPetId,
                MessageType.TEXT,
                request.body(),
                null,
                null,
                request.clientMessageId(),
                null,
                null,
                null
        );
    }

    /**
     * Announce a meeting card in its room. Server-side only.
     */
    @Transactional
    public ChatMessageResult postCard(long roomId, long creatorPetId, long meetingCardId, String clientMessageId) {
        return insert(roomId, SenderType.PET, creatorPetId,
                MessageType.CARD, null, meetingCardId, null, clientMessageId,
                null, null, null);
    }

    /**
     * Publish a system notice. Server-side only.
     */
    @Transactional
    public ChatMessageResult postSystem(long roomId, String body, String clientMessageId) {
        return insert(roomId, SenderType.SYSTEM, null,
                MessageType.SYSTEM, body, null, null, clientMessageId,
                null, null, null);
    }

    private ChatMessageResult sendMedia(long roomId, long senderPetId, long ownerUserId,
                                        ChatMessageCreateRequest request, AttachmentType attachmentType) {
        requireClientMessageId(request);
        MediaType expected = attachmentType == AttachmentType.IMAGE ? MediaType.IMAGE : MediaType.VIDEO;
        return insert(roomId, SenderType.PET, senderPetId, request.type(), request.body(),
                null, null, request.clientMessageId(), request.mediaId(), attachmentType,
                () -> mediaService.requireOwnedPlayableMedia(request.mediaId(), ownerUserId, expected));
    }

    private ChatMessageResult sendSetlogShare(long roomId, long senderPetId, long ownerUserId,
                                              ChatMessageCreateRequest request) {
        requireClientMessageId(request);
        return insert(roomId, SenderType.PET, senderPetId, request.type(), request.body(),
                null, request.setlogId(), request.clientMessageId(), null, null,
                () -> setlogQueryService.requireShareableSetlog(request.setlogId(), ownerUserId));
    }

    /**
     * Shared write path for every message type.
     *
     * <p>Resource validation(media/setlog)은 방 존재·참여 검증 뒤에 실행한다. 비참여자가 media/setlog
     * 존재 여부를 오류 차이로 추측하는 side channel을 만들지 않기 위함이다.
     */
    private ChatMessageResult insert(long roomId, SenderType senderType, Long senderPetId,
                                     MessageType type, String body, Long meetingCardId,
                                     Long sharedSetlogId, String clientMessageId,
                                     Long mediaId, AttachmentType attachmentType,
                                     Runnable resourceValidation) {
        requireValidPayload(type, body, sharedSetlogId, mediaId, clientMessageId);

        // Fast path: a message already stored under this key wins outright.
        if (clientMessageId != null) {
            var existing = chatMessageRepository.findByRoomIdAndClientMessageId(roomId, clientMessageId);
            if (existing.isPresent()) {
                ChatMessage found = existing.get();
                requireSamePayload(found, senderPetId, type, body, meetingCardId, sharedSetlogId, mediaId);
                return new ChatMessageResult(found, false);
            }
        }

        chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (senderPetId != null
                && !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, senderPetId)) {
            throw new BusinessException(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);
        }

        if (resourceValidation != null) {
            resourceValidation.run();
        }

        MessageUpsert upsert = chatMessageRepository.insertMessageOnConflictWithReturning(
                roomId, senderType.name(), senderPetId, type.name(), body, meetingCardId, sharedSetlogId, clientMessageId);

        ChatMessage message = chatMessageRepository.findById(upsert.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        boolean created = Boolean.TRUE.equals(upsert.getCreated());
        // Losing the insert race returns the winner's row, which may not be ours. The winner's row
        // (message + attachment) is already committed by the time this loser reaches here, so the
        // attachment comparison is reliable. The winner skips this check: it just wrote these values.
        if (!created) {
            requireSamePayload(message, senderPetId, type, body, meetingCardId, sharedSetlogId, mediaId);
        }
        if (created) {
            if (mediaId != null) {
                attachmentRepository.save(ChatMessageAttachment.attach(message, mediaId, attachmentType));
            }
            chatRoomRepository.activateAndTouchLastMessageAt(roomId);
            eventPublisher.publishEvent(new ChatMessageCommittedEvent(
                    message.getRoom().getType(),
                    responseAssembler.toResponse(message, senderPetNickname(message.getSenderPetId()))
            ));
        }
        return new ChatMessageResult(message, created);
    }

    private String senderPetNickname(Long senderPetId) {
        if (senderPetId == null) {
            return null;
        }
        return petRepository.findById(senderPetId)
                .map(Pet::getNickname)
                .orElse(null);
    }

    private void requireClientMessageId(ChatMessageCreateRequest request) {
        if (request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
        }
    }

    private void requireValidPayload(MessageType type, String body, Long sharedSetlogId,
                                     Long mediaId, String clientMessageId) {
        switch (type) {
            case TEXT -> {
                if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
                if (mediaId != null || sharedSetlogId != null) {
                    throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
                }
            }
            case CARD -> {
                if (body != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            }
            case SYSTEM -> {
                if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            }
            case IMAGE, VIDEO -> {
                if (body != null || sharedSetlogId != null) {
                    throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
                }
                if (mediaId == null) {
                    throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
                }
            }
            case SETLOG_SHARE -> {
                if (body != null || mediaId != null) {
                    throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
                }
                if (sharedSetlogId == null) {
                    throw new BusinessException(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
                }
            }
        }
        if (clientMessageId != null && clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * An idempotency key identifies one specific message. Reusing it with different content is a
     * client bug rather than a retry, so reject it instead of silently returning something else.
     */
    private void requireSamePayload(ChatMessage stored, Long senderPetId, MessageType type,
                                    String body, Long meetingCardId, Long sharedSetlogId, Long mediaId) {
        if (stored.getType() != type
                || !Objects.equals(stored.getSenderPetId(), senderPetId)
                || !Objects.equals(stored.getBody(), body)
                || !Objects.equals(stored.getMeetingCardId(), meetingCardId)
                || !Objects.equals(stored.getSharedSetlogId(), sharedSetlogId)) {
            throw new BusinessException(ErrorCode.CHAT_DUPLICATE_MESSAGE);
        }
        if (type == MessageType.IMAGE || type == MessageType.VIDEO) {
            Long storedMediaId = attachmentRepository.findByMessageId(stored.getId())
                    .map(ChatMessageAttachment::getMediaId)
                    .orElse(null);
            if (!Objects.equals(storedMediaId, mediaId)) {
                throw new BusinessException(ErrorCode.CHAT_DUPLICATE_MESSAGE);
            }
        }
    }
}
