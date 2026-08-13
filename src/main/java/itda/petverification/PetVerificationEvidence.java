package itda.petverification;

import itda.pet.domain.PetSex;
import itda.petverification.domain.PetVerification.Evidence;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import java.time.LocalDate;

public record PetVerificationEvidence(
        PetVerificationProvider provider,
        String registrationNumberHmac,
        PetVerificationDeviceType deviceType,
        String registeredName,
        LocalDate birthDate,
        PetSex sex,
        String breedName,
        Boolean neutered
) {
    public PetVerificationEvidence {
        if (provider != PetVerificationProvider.ANIMAL_INFO_V3
                || registrationNumberHmac == null
                || !registrationNumberHmac.matches("^[0-9a-f]{64}$")
                || sex == PetSex.UNKNOWN) {
            throw new IllegalArgumentException("Invalid pet verification evidence");
        }
    }

    public Evidence toEntityEvidence() {
        return new Evidence(provider, registrationNumberHmac, deviceType, registeredName,
                birthDate, sex, breedName, neutered);
    }
}
