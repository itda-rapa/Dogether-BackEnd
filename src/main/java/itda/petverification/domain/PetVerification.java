package itda.petverification.domain;

import itda.common.BaseEntity;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "pet_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PetVerification extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PetVerificationProvider provider;

    @Column(name = "registration_number_hmac", nullable = false, length = 64)
    private String registrationNumberHmac;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private PetVerificationDeviceType deviceType;

    @Column(name = "registered_name", length = 100)
    private String registeredName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PetSex sex;

    @Column(name = "breed_name", length = 100)
    private String breedName;

    private Boolean neutered;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    private PetVerification(Pet pet, Evidence evidence) {
        this.pet = pet;
        this.provider = evidence.provider();
        this.registrationNumberHmac = evidence.registrationNumberHmac();
        this.deviceType = evidence.deviceType();
        this.registeredName = evidence.registeredName();
        this.birthDate = evidence.birthDate();
        this.sex = evidence.sex();
        this.breedName = evidence.breedName();
        this.neutered = evidence.neutered();
        this.verifiedAt = Instant.now();
    }

    public static PetVerification create(Pet pet, Evidence evidence) {
        return new PetVerification(pet, evidence);
    }

    public record Evidence(
            PetVerificationProvider provider,
            String registrationNumberHmac,
            PetVerificationDeviceType deviceType,
            String registeredName,
            LocalDate birthDate,
            PetSex sex,
            String breedName,
            Boolean neutered
    ) {
    }
}
