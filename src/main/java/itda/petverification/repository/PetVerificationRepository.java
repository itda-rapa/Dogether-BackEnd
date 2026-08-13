package itda.petverification.repository;

import itda.petverification.domain.PetVerification;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetVerificationRepository extends JpaRepository<PetVerification, Long> {
    boolean existsByPet_Id(Long petId);

    boolean existsByRegistrationNumberHmac(String registrationNumberHmac);

    Optional<PetVerification> findByPet_Id(Long petId);

    @Query("select verification.pet.id as petId, verification.verifiedAt as verifiedAt "
            + "from PetVerification verification where verification.pet.id in :petIds")
    List<PetVerificationBadgeRow> findBadgeRowsByPetIds(@Param("petIds") Collection<Long> petIds);

    interface PetVerificationBadgeRow {
        Long getPetId();
        Instant getVerifiedAt();
    }
}
