package itda.chat.domain;

import itda.common.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "chat_rooms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomOrigin origin;

    @Column(name = "pet_low_id")
    private Long petLowId;

    @Column(name = "pet_high_id")
    private Long petHighId;

    @Column(length = 120)
    private String title;

    @Column(length = 500)
    private String description;

    @Column
    private Long ownerPetId;

    @Column
    private Integer maxParticipants;

    @Column
    private Boolean isPublic;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @OneToMany(mappedBy = "room")
    private List<ChatMessage> messages = new ArrayList<>();

    @OneToMany(mappedBy = "room")
    private List<ChatRoomParticipant> participants = new ArrayList<>();


    public ChatRoom(
            RoomType type,
            RoomStatus status,
            RoomOrigin origin,
            Long petLowId,
            Long petHighId,
            String title,
            String description,
            Long ownerPetId,
            Integer maxParticipants,
            Boolean isPublic
    ){
        this.type = type;
        this.status = status;
        this.origin = origin;
        this.petLowId = petLowId;
        this.petHighId = petHighId;
        this.title = title;
        this.description = description;
        this.ownerPetId = ownerPetId;
        this.maxParticipants = maxParticipants;
        this.isPublic = isPublic;
    }

    public static ChatRoom openChat(
            String title,
            String description,
            long ownerPetId,
            int maxParticipants,
            boolean isPublic
    ) {
        return new ChatRoom(
                RoomType.GROUP,
                RoomStatus.ACTIVE,
                RoomOrigin.OPEN_CHAT,
                null,
                null,
                title,
                description,
                ownerPetId,
                maxParticipants,
                isPublic
        );
    }

    public void updateOpenChat(
            String title,
            String description,
            int maxParticipants,
            boolean isPublic
    ) {
        this.title = title;
        this.description = description;
        this.maxParticipants = maxParticipants;
        this.isPublic = isPublic;
    }

    public boolean isOpenChat() {
        return type == RoomType.GROUP && origin == RoomOrigin.OPEN_CHAT;
    }

    public boolean isOwnedBy(long petId) {
        return Objects.equals(ownerPetId, petId);
    }

    public void archive(Instant archivedAt) {
        this.status = RoomStatus.ARCHIVED;
        this.archivedAt = archivedAt;
    }

    public void addParticipant(ChatRoomParticipant participant) {
        participants.add(participant);
    }

}
