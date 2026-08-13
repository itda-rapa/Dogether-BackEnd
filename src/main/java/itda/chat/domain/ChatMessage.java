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

import lombok.*;
import lombok.experimental.Accessors;

@Getter
@Entity
@Setter
@Table(name = "chat_messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(chain = true)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private SenderType senderType;

    @Column(name = "sender_pet_id")
    private Long senderPetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    @Column(length = 2000)
    private String body;

    @Column(name = "meeting_card_id")
    private Long meetingCardId;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private ChatMessage(
            ChatRoom room,
            String message
    ){
        this.room = room;
        this.senderType = SenderType.PET;
        this.type = MessageType.TEXT;
        this.body = message;
        this.createdAt = Instant.now();
    }

    public static ChatMessage fromPet(
            ChatRoom chatRoom,
            String message,
            Long petId
    ){
        return ChatMessage.builder()
                .room(chatRoom)
                .message(message)
                .build()
                .setSenderPetId(petId);
    }

}
