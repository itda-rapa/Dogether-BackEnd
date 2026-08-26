package itda.user.domain;

import itda.chat.domain.ChatRoomParticipant;
import itda.common.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "public_tag", nullable = false, unique = true, length = 30)
    private String publicTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Column(name = "neighborhood_code", nullable = false, length = 20)
    private String neighborhoodCode;

    @Column(name = "active_pet_id")
    private Long activePetId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    private User(
            String email,
            String passwordHash,
            String nickname,
            String publicTag,
            String neighborhoodCode
    ) {
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.publicTag = publicTag;
        this.neighborhoodCode = neighborhoodCode;
    }

    public static User register(
            String email,
            String passwordHash,
            String nickname,
            String publicTag,
            String neighborhoodCode
    ) {
        return new User(
                email,
                passwordHash,
                nickname,
                publicTag,
                neighborhoodCode
        );
    }

    /**
     * Creates an account whose authentication credential is held by an OAuth provider.
     * A password must not be synthesized for these accounts.
     */
    public static User registerOAuth(
            String email,
            String nickname,
            String publicTag,
            String neighborhoodCode
    ) {
        return new User(
                email,
                null,
                nickname,
                publicTag,
                neighborhoodCode
        );
    }

    public boolean isActive() {
        return accountStatus == AccountStatus.ACTIVE;
    }

    public boolean hasPasswordCredential() {
        return passwordHash != null;
    }

    public void selectActivePet(Long petId) {
        if (petId == null) {
            throw new NullPointerException("petId는 null일 수 없습니다.");
        }

        if (Objects.equals(activePetId, petId)) {
            return;
        }

        activePetId = petId;
    }

    public boolean hasActivePet() {
        return activePetId != null;
    }

    public boolean isActivePet(Long petId) {
        return activePetId != null
                && Objects.equals(activePetId, petId);
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void changePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash는 비어 있을 수 없습니다.");
        }
        this.passwordHash = passwordHash;
    }
}
