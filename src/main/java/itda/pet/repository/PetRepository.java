package itda.pet.repository;

import itda.pet.domain.Pet;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
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

    @Query("""
            select pet
            from Pet pet
            join fetch pet.owner owner
            where owner.id = :userId
              and pet.deletedAt is null
            order by
              case when owner.activePetId = pet.id then 0 else 1 end,
              pet.createdAt asc,
              pet.id asc
            """)
    List<Pet> findMyPetsOrdered(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pet from Pet pet where pet.id = :petId")
    Optional<Pet> findByIdForUpdate(@Param("petId") Long petId);

    @Query(value = """
            SELECT
                pet.id AS petId,
                pet.owner_user_id AS ownerUserId
            FROM pets pet
            WHERE pet.id IN (:petIds)
            """, nativeQuery = true)
    List<PetOwnerRow> findOwnerRows(@Param("petIds") Collection<Long> petIds);

    @Query(value = """
            SELECT
                pet.id AS petId,
                pet.owner_user_id AS ownerUserId,
                pet.status AS status,
                pet.deleted_at AS deletedAt
            FROM pets pet
            WHERE pet.id = :petId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedPetRow> findLockedPetRow(@Param("petId") Long petId);

    interface PetOwnerRow {

        Long getPetId();

        Long getOwnerUserId();
    }

    interface LockedPetRow {

        Long getPetId();

        Long getOwnerUserId();

        String getStatus();

        Instant getDeletedAt();
    }
}
