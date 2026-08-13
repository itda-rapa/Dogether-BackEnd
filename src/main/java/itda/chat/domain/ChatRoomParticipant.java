package itda.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "chat_room_participants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    public static ChatRoomParticipant join(
            ChatRoom room,
            long petId
    ) {
        ChatRoomParticipant participant = new ChatRoomParticipant();
        participant.room = room;
        participant.petId = petId;
        participant.joinedAt = Instant.now();
        room.addParticipant(participant);
        return participant;
    }

    public void rejoin() {
        this.leftAt = null;
    }

    public void leave(Instant leftAt) {
        this.leftAt = leftAt;
    }
}
