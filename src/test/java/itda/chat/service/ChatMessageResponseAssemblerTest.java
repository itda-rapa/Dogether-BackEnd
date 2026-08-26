package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.chat.domain.AttachmentType;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatMessageAttachment;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.repository.ChatMessageAttachmentRepository;
import itda.media.domain.Media;
import itda.media.domain.MediaType;
import itda.media.service.MediaService;
import itda.media.service.MediaService.OwnedPresignedDownload;
import itda.media.service.MediaService.PresignedDownloadUrl;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.service.SetlogQueryService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatMessageResponseAssemblerTest {

    @Test
    void batchHydratesAttachmentsOnceAndKeepsUnavailableMediaIdentifiers() {
        ChatMessageAttachmentRepository attachmentRepository = mock(ChatMessageAttachmentRepository.class);
        MediaService mediaService = mock(MediaService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatMessageResponseAssembler assembler = new ChatMessageResponseAssembler(
                attachmentRepository, mediaService, setlogQueryService);

        ChatMessage imageMessage = mediaMessage(11L, MessageType.IMAGE);
        ChatMessage videoMessage = mediaMessage(12L, MessageType.VIDEO);
        ChatMessageAttachment imageAttachment = attachment(imageMessage, 101L, AttachmentType.IMAGE);
        ChatMessageAttachment unavailableVideoAttachment = attachment(videoMessage, 202L, AttachmentType.VIDEO);
        Media image = media(101L, MediaType.IMAGE, "image/png", 2048L);

        when(attachmentRepository.findAllByMessageIdIn(List.of(11L, 12L)))
                .thenReturn(List.of(imageAttachment, unavailableVideoAttachment));
        when(mediaService.getMediaDownloadsByIds(anyCollection())).thenReturn(Map.of(
                101L,
                new OwnedPresignedDownload(
                        image,
                        new PresignedDownloadUrl(
                                "https://storage.example/chat-image",
                                Instant.parse("2026-08-26T03:00:00Z")
                        )
                )
        ));

        List<ChatMessageResponse> result = assembler.toResponses(
                List.of(imageMessage, videoMessage), Map.<Long, PetDisplaySummary>of(), 1L, "나");

        ArgumentCaptor<Collection<Long>> mediaIds = ArgumentCaptor.forClass(Collection.class);
        verify(attachmentRepository).findAllByMessageIdIn(List.of(11L, 12L));
        verify(mediaService).getMediaDownloadsByIds(mediaIds.capture());
        verifyNoInteractions(setlogQueryService);
        assertThat(mediaIds.getValue()).containsExactlyInAnyOrder(101L, 202L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).attachment())
                .extracting("mediaId", "mediaType", "contentType", "fileSize", "url", "expiresAt")
                .containsExactly(
                        101L, "IMAGE", "image/png", 2048L,
                        "https://storage.example/chat-image", Instant.parse("2026-08-26T03:00:00Z"));
        assertThat(result.get(1).attachment())
                .extracting("mediaId", "mediaType", "contentType", "fileSize", "url", "expiresAt")
                .containsExactly(202L, "VIDEO", null, null, null, null);
    }

    @Test
    void batchHydrationDeduplicatesMediaIdsBeforeMediaLookup() {
        ChatMessageAttachmentRepository attachmentRepository = mock(ChatMessageAttachmentRepository.class);
        MediaService mediaService = mock(MediaService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatMessageResponseAssembler assembler = new ChatMessageResponseAssembler(
                attachmentRepository, mediaService, setlogQueryService);
        ChatMessage first = mediaMessage(11L, MessageType.IMAGE);
        ChatMessage second = mediaMessage(12L, MessageType.IMAGE);
        ChatMessageAttachment firstAttachment = attachment(first, 101L, AttachmentType.IMAGE);
        ChatMessageAttachment secondAttachment = attachment(second, 101L, AttachmentType.IMAGE);

        when(attachmentRepository.findAllByMessageIdIn(List.of(11L, 12L)))
                .thenReturn(List.of(firstAttachment, secondAttachment));
        when(mediaService.getMediaDownloadsByIds(anyCollection())).thenReturn(Map.of());

        assembler.toResponses(List.of(first, second), Map.of(), 1L, "나");

        ArgumentCaptor<Collection<Long>> mediaIds = ArgumentCaptor.forClass(Collection.class);
        verify(mediaService).getMediaDownloadsByIds(mediaIds.capture());
        assertThat(mediaIds.getValue()).containsExactly(101L);
    }

    private ChatMessage mediaMessage(long id, MessageType type) {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage message = mock(ChatMessage.class);
        when(room.getId()).thenReturn(7L);
        when(message.getId()).thenReturn(id);
        when(message.getRoom()).thenReturn(room);
        when(message.getSenderType()).thenReturn(SenderType.PET);
        when(message.getSenderPetId()).thenReturn(1L);
        when(message.getType()).thenReturn(type);
        when(message.getClientMessageId()).thenReturn("client-" + id);
        when(message.getCreatedAt()).thenReturn(Instant.parse("2026-08-26T02:00:00Z"));
        return message;
    }

    private ChatMessageAttachment attachment(
            ChatMessage message,
            long mediaId,
            AttachmentType attachmentType
    ) {
        ChatMessageAttachment attachment = mock(ChatMessageAttachment.class);
        when(attachment.getMessage()).thenReturn(message);
        when(attachment.getMediaId()).thenReturn(mediaId);
        when(attachment.getAttachmentType()).thenReturn(attachmentType);
        return attachment;
    }

    private Media media(long id, MediaType type, String contentType, long fileSize) {
        Media media = mock(Media.class);
        when(media.getId()).thenReturn(id);
        when(media.getMediaType()).thenReturn(type);
        when(media.getContentType()).thenReturn(contentType);
        when(media.getFileSize()).thenReturn(fileSize);
        return media;
    }
}
