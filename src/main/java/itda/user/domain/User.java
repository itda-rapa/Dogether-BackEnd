package itda.user.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
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

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

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
            String neighborhoodCode
    ) {
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.neighborhoodCode = neighborhoodCode;
    }

    public static User register(
            String email,
            String passwordHash,
            String nickname,
            String neighborhoodCode
    ) {
        return new User(email, passwordHash, nickname, neighborhoodCode);
    }

    public boolean isActive() {
        return accountStatus == AccountStatus.ACTIVE;
    }

    public void changeRole(Role role) {
        this.role = role;
    }
}
