package itda.pet.domain;

import itda.common.BaseEntity;
import itda.media.domain.Media;
import itda.user.domain.User;
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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "pets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(name = "public_tag", nullable = false, unique = true, length = 30)
    private String publicTag;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "breed_name", length = 100)
    private String breedName;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PetSex sex;

    private Boolean neutered;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_code", length = 20)
    private PetSizeCode sizeCode;

    @Column(length = 500)
    private String bio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "personality_tags")
    private List<String> personalityTags;

    @Column(name = "care_note", length = 500)
    private String careNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PetStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_asset_id")
    private Media profileAsset;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Pet(
            User owner,
            String publicTag,
            String nickname,
            String breedName,
            PetSex sex,
            Boolean neutered,
            LocalDate birthDate,
            BigDecimal weightKg,
            PetSizeCode sizeCode,
            String bio,
            List<String> personalityTags,
            String careNote
    ) {
        this.owner = owner;
        this.publicTag = publicTag;
        this.nickname = nickname;
        this.breedName = breedName;
        this.sex = sex;
        this.neutered = neutered;
        this.birthDate = birthDate;
        this.weightKg = weightKg;
        this.sizeCode = sizeCode;
        this.bio = bio;
        this.personalityTags = copyPersonalityTags(personalityTags);
        this.careNote = careNote;
        this.status = PetStatus.ACTIVE;
        this.profileAsset = null;
        this.deletedAt = null;
    }

    public static Pet register(
            User owner,
            String publicTag,
            String nickname,
            String breedName,
            PetSex sex,
            Boolean neutered,
            LocalDate birthDate,
            BigDecimal weightKg,
            PetSizeCode sizeCode,
            String bio,
            List<String> personalityTags,
            String careNote
    ) {
        return new Pet(
                owner,
                publicTag,
                nickname,
                breedName,
                sex,
                neutered,
                birthDate,
                weightKg,
                sizeCode,
                bio,
                personalityTags,
                careNote
        );
    }

    public List<String> getPersonalityTags() {
        return copyPersonalityTags(personalityTags);
    }

    public boolean belongsTo(Long userId) {
        return owner.getId().equals(userId);
    }

    public boolean isActive() {
        return status == PetStatus.ACTIVE;
    }

    public boolean isDeleted() {
        return status == PetStatus.DELETED;
    }

    public void changeNickname(String nickname) {
        if (!Objects.equals(this.nickname, nickname)) {
            this.nickname = nickname;
        }
    }

    public void changeBreedName(String breedName) {
        if (!Objects.equals(this.breedName, breedName)) {
            this.breedName = breedName;
        }
    }

    public void changeSex(PetSex sex) {
        if (!Objects.equals(this.sex, sex)) {
            this.sex = sex;
        }
    }

    public void changeNeutered(Boolean neutered) {
        if (!Objects.equals(this.neutered, neutered)) {
            this.neutered = neutered;
        }
    }

    public void changeBirthDate(LocalDate birthDate) {
        if (!Objects.equals(this.birthDate, birthDate)) {
            this.birthDate = birthDate;
        }
    }

    public void changeWeightKg(BigDecimal weightKg) {
        if (!sameDecimal(this.weightKg, weightKg)) {
            this.weightKg = weightKg;
        }
    }

    public void changeSizeCode(PetSizeCode sizeCode) {
        if (!Objects.equals(this.sizeCode, sizeCode)) {
            this.sizeCode = sizeCode;
        }
    }

    public void changeBio(String bio) {
        if (!Objects.equals(this.bio, bio)) {
            this.bio = bio;
        }
    }

    public void changePersonalityTags(List<String> personalityTags) {
        if (!Objects.equals(this.personalityTags, personalityTags)) {
            this.personalityTags = copyPersonalityTags(personalityTags);
        }
    }

    public void changeCareNote(String careNote) {
        if (!Objects.equals(this.careNote, careNote)) {
            this.careNote = careNote;
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

    private static List<String> copyPersonalityTags(
            List<String> personalityTags
    ) {
        return personalityTags == null ? null : List.copyOf(personalityTags);
    }
}
