package itda.pet.repository;

import itda.pet.domain.Pet;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetRepository extends JpaRepository<Pet, Long> {

    boolean existsByPublicTag(String publicTag);

    long countByOwner_IdAndDeletedAtIsNull(Long ownerUserId);

    List<Pet> findAllByOwner_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
            Long ownerUserId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pet from Pet pet where pet.id = :petId")
    Optional<Pet> findByIdForUpdate(@Param("petId") Long petId);
}
