package itda.chat.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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

    @Column(name = "pet_low_id", nullable = false)
    private Long petLowId;

    @Column(name = "pet_high_id", nullable = false)
    private Long petHighId;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * DIRECT rooms are created through {@code ChatRoomService.ensureDirectRoom}, which uses an
     * atomic {@code INSERT ... ON CONFLICT} against {@code uk_chat_room_direct_pair}. Pair
     * normalization and self-pair rejection live there so the concurrency gate cannot be bypassed.
     *
     * <p>{@code last_message_at} is likewise advanced by {@code ChatRoomRepository.touchLastMessageAt},
     * which uses {@code GREATEST} so a late-committing message never moves the timestamp backwards.
     */
    public void archive() {
        this.status = RoomStatus.ARCHIVED;
        this.archivedAt = Instant.now();
    }
}
