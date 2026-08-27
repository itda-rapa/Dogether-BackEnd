package itda.chat.service;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatMessageAttachment;
import itda.chat.domain.MessageType;
import itda.chat.dto.response.ChatMessageAttachmentResponse;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.ChatMapMessageResponse;
import itda.chat.dto.response.MapFacilitySnapshot;
import itda.chat.dto.response.SetlogMediaResponse;
import itda.chat.dto.response.SharedSetlogResponse;
import itda.chat.repository.ChatMessageAttachmentRepository;
import itda.media.service.MediaService;
import itda.media.service.MediaService.OwnedPresignedDownload;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.dto.ShareableSetlogView;
import itda.setlog.service.SetlogQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * ChatMessage를 외부 응답 DTO로 변환하면서 IMAGE/VIDEO의 media와 SETLOG_SHARE의 setlog를
 * batch hydrate한다. Presigned URL은 응답 시점에 발급하며 영구 저장하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageResponseAssembler {

    private final ChatMessageAttachmentRepository attachmentRepository;
    private final MediaService mediaService;
    private final SetlogQueryService setlogQueryService;
    private final SharedSetlogResponseMapper sharedSetlogResponseMapper;
    private final ObjectMapper objectMapper;

    /**
     * 단일 메시지 변환(전송 응답·실시간 이벤트). 이미 알려진 발신자 닉네임을 받는다.
     */
    public ChatMessageResponse toResponse(ChatMessage message, String senderPetNickname) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSenderType().name(),
                message.getSenderPetId(),
                senderPetNickname,
                message.getType().name(),
                message.getBody(),
                attachmentOf(message),
                sharedSetlogOf(message),
                mapOf(message),
                message.getMeetingCardId(),
                message.getClientMessageId(),
                message.getCreatedAt()
        );
    }

    /**
     * 목록 변환(폴링). IMAGE/VIDEO media와 SETLOG_SHARE setlog를 한 번에 hydrate해 N+1을 피한다.
     */
    /**
     * 목록 변환에서 조회자 User를 함께 받아 SETLOG_SHARE의 차단 접근 정책까지 적용한다.
     */
    public List<ChatMessageResponse> toResponses(
            List<ChatMessage> messages,
            Map<Long, PetDisplaySummary> senderPets,
            long actorPetId,
            String actorNickname,
            Long viewerUserId
    ) {
        Map<Long, ChatMessageAttachment> attachments = attachmentsOf(messages);
        Map<Long, OwnedPresignedDownload> mediaDownloads =
                mediaService.getMediaDownloadsByIds(
                        attachments.values().stream()
                                .map(ChatMessageAttachment::getMediaId)
                                .distinct()
                                .toList()
                );
        Map<Long, ShareableSetlogView> setlogViews = setlogViewsOf(messages, viewerUserId);

        return messages.stream()
                .map(message -> toResponse(
                        message,
                        senderPetNickname(message.getSenderPetId(), actorPetId, senderPets, actorNickname),
                        attachments.get(message.getId()),
                        mediaDownloads,
                        setlogViews
                ))
                .collect(Collectors.toList());
    }

    private ChatMessageResponse toResponse(
            ChatMessage message,
            String senderPetNickname,
            ChatMessageAttachment attachment,
            Map<Long, OwnedPresignedDownload> mediaDownloads,
            Map<Long, ShareableSetlogView> setlogViews
    ) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSenderType().name(),
                message.getSenderPetId(),
                senderPetNickname,
                message.getType().name(),
                message.getBody(),
                attachmentOf(attachment, mediaDownloads),
                sharedSetlogOf(message.getType(), message.getSharedSetlogId(), setlogViews),
                mapOf(message),
                message.getMeetingCardId(),
                message.getClientMessageId(),
                message.getCreatedAt()
        );
    }

    private ChatMapMessageResponse mapOf(ChatMessage message) {
        if (message.getType() != MessageType.MAP) {
            return null;
        }
        try {
            List<MapFacilitySnapshot> facilities = objectMapper.readValue(
                    message.getMapFacilitiesJson(), new TypeReference<>() { });
            return new ChatMapMessageResponse(message.getMapCategory(), facilities);
        } catch (Exception exception) {
            throw new IllegalStateException("저장된 지도 메시지를 읽을 수 없습니다.", exception);
        }
    }

    private ChatMessageAttachmentResponse attachmentOf(ChatMessage message) {
        MessageType type = message.getType();
        if (type != MessageType.IMAGE && type != MessageType.VIDEO) {
            return null;
        }
        return attachmentRepository.findByMessageId(message.getId())
                .map(attachment -> attachmentOf(
                        attachment,
                        mediaService.getMediaDownloadsByIds(List.of(attachment.getMediaId()))
                ))
                .orElse(null);
    }

    private ChatMessageAttachmentResponse attachmentOf(
            ChatMessageAttachment attachment,
            Map<Long, OwnedPresignedDownload> downloads
    ) {
        if (attachment == null) {
            return null;
        }
        OwnedPresignedDownload download = downloads.get(attachment.getMediaId());
        if (download == null) {
            return new ChatMessageAttachmentResponse(
                    attachment.getMediaId(), attachment.getAttachmentType().name(), null, null, null, null);
        }
        return new ChatMessageAttachmentResponse(
                download.media().getId(),
                download.media().getMediaType().name(),
                download.media().getContentType(),
                download.media().getFileSize(),
                download.download().url(),
                download.download().expiresAt()
        );
    }

    private SharedSetlogResponse sharedSetlogOf(ChatMessage message) {
        return sharedSetlogOf(
                message.getType(),
                message.getSharedSetlogId(),
                message.getSharedSetlogId() == null
                        ? Map.of()
                        : setlogQueryService.findShareableSetlogViews(List.of(message.getSharedSetlogId()))
        );
    }

    private SharedSetlogResponse sharedSetlogOf(
            MessageType type,
            Long setlogId,
            Map<Long, ShareableSetlogView> views
    ) {
        if (type != MessageType.SETLOG_SHARE || setlogId == null) {
            return null;
        }
        ShareableSetlogView view = views.getOrDefault(setlogId, ShareableSetlogView.unavailable(setlogId));
        return sharedSetlogResponseMapper.toResponse(view);
    }

    private Map<Long, ChatMessageAttachment> attachmentsOf(List<ChatMessage> messages) {
        List<Long> messageIds = messages.stream()
                .filter(m -> m.getType() == MessageType.IMAGE || m.getType() == MessageType.VIDEO)
                .map(ChatMessage::getId)
                .toList();
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ChatMessageAttachment> result = new LinkedHashMap<>();
        for (ChatMessageAttachment attachment : attachmentRepository.findAllByMessageIdIn(messageIds)) {
            result.put(attachment.getMessage().getId(), attachment);
        }
        return Map.copyOf(result);
    }

    private Map<Long, ShareableSetlogView> setlogViewsOf(
            List<ChatMessage> messages,
            Long viewerUserId
    ) {
        List<Long> setlogIds = messages.stream()
                .filter(m -> m.getType() == MessageType.SETLOG_SHARE && m.getSharedSetlogId() != null)
                .map(ChatMessage::getSharedSetlogId)
                .distinct()
                .toList();
        if (setlogIds.isEmpty()) {
            return Map.of();
        }
        return setlogQueryService.findShareableSetlogViews(setlogIds, viewerUserId);
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
