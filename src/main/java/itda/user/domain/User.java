package itda.user.domain;

import itda.chat.domain.ChatRoomParticipant;
import itda.common.BaseEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
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

    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("1.00");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("500.00");

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

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

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
        return register(
                email,
                passwordHash,
                nickname,
                publicTag,
                neighborhoodCode,
                null
        );
    }

    public static User register(
            String email,
            String passwordHash,
            String nickname,
            String publicTag,
            String neighborhoodCode,
            BigDecimal weightKg
    ) {
        validateWeightKg(weightKg);

        User user = new User(
                email,
                passwordHash,
                nickname,
                publicTag,
                neighborhoodCode
        );
        user.weightKg = weightKg;
        return user;
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
        return registerOAuth(
                email,
                nickname,
                publicTag,
                neighborhoodCode,
                null
        );
    }

    public static User registerOAuth(
            String email,
            String nickname,
            String publicTag,
            String neighborhoodCode,
            BigDecimal weightKg
    ) {
        validateWeightKg(weightKg);

        User user = new User(
                email,
                null,
                nickname,
                publicTag,
                neighborhoodCode
        );
        user.weightKg = weightKg;
        return user;
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

    public boolean changeNickname(String nickname) {
        String normalizedNickname = normalizeNickname(nickname);
        if (Objects.equals(this.nickname, normalizedNickname)) {
            return false;
        }
        this.nickname = normalizedNickname;
        return true;
    }

    public boolean changeNeighborhoodCode(String neighborhoodCode) {
        if (neighborhoodCode == null) {
            throw new NullPointerException("neighborhoodCode는 null일 수 없습니다.");
        }

        String normalizedNeighborhoodCode = neighborhoodCode.trim();
        if (normalizedNeighborhoodCode.isEmpty()) {
            throw new IllegalArgumentException("neighborhoodCode는 비어 있을 수 없습니다.");
        }
        if (Objects.equals(this.neighborhoodCode, normalizedNeighborhoodCode)) {
            return false;
        }
        this.neighborhoodCode = normalizedNeighborhoodCode;
        return true;
    }

    public boolean changeWeightKg(BigDecimal weightKg) {
        validateWeightKg(weightKg);
        if (sameDecimal(this.weightKg, weightKg)) {
            return false;
        }
        this.weightKg = weightKg;
        return true;
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw new NullPointerException("nickname은 null일 수 없습니다.");
        }

        String normalizedNickname = nickname.trim();
        if (normalizedNickname.length() < 2 || normalizedNickname.length() > 20) {
            throw new IllegalArgumentException("nickname은 trim 후 2자 이상 20자 이하여야 합니다.");
        }
        return normalizedNickname;
    }

    private static void validateWeightKg(BigDecimal weightKg) {
        if (weightKg == null) {
            return;
        }
        if (weightKg.compareTo(MIN_WEIGHT_KG) < 0
                || weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new IllegalArgumentException("weightKg는 1.00 이상 500.00 이하여야 합니다.");
        }
        if (weightKg.scale() > 2) {
            throw new IllegalArgumentException("weightKg는 소수 둘째 자리까지만 허용합니다.");
        }
    }

    private static boolean sameDecimal(
            BigDecimal currentValue,
            BigDecimal newValue
    ) {
        if (currentValue == null || newValue == null) {
            return currentValue == newValue;
        }
        return currentValue.compareTo(newValue) == 0;
    }
}
