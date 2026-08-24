package itda.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * IMAGE/VIDEO 메시지의 media 첨부. media는 별도 도메인이므로 FK id만 보관하고,
 * 조회 시 media 공개 계약으로 batch hydrate한다(영속 URL은 저장하지 않는다).
 */
@Getter
@Entity
@Table(name = "chat_message_attachments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Column(name = "media_id", nullable = false)
    private Long mediaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attachment_type", nullable = false, length = 20)
    private AttachmentType attachmentType;

    @Column(name = "display_order", nullable = false)
    private Short displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ChatMessageAttachment(ChatMessage message, Long mediaId, AttachmentType attachmentType) {
        this.message = message;
        this.mediaId = mediaId;
        this.attachmentType = attachmentType;
        this.displayOrder = 0;
        this.createdAt = Instant.now();
    }

    public static ChatMessageAttachment attach(ChatMessage message, Long mediaId, AttachmentType attachmentType) {
        return new ChatMessageAttachment(message, mediaId, attachmentType);
    }
}
