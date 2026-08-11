package itda.greeting.repository;

import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface GreetingRepository extends JpaRepository<Greeting, Long> {

    boolean existsByFromPet_IdAndToPet_Id(
            Long fromPetId,
            Long toPetId
    );

    long countByFromPet_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long fromPetId,
            Instant startInclusive,
            Instant endExclusive
    );

    Optional<Greeting> findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
            Long roomId,
            Long toPetId,
            GreetingStatus status
    );

    boolean existsByRoomIdAndFromPet_IdAndStatus(
            Long roomId,
            Long fromPetId,
            GreetingStatus status
    );

    boolean existsByRoomIdAndStatus(
            Long roomId,
            GreetingStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Greeting g where g.id = :greetingId")
    Optional<Greeting> findByIdForUpdate(@Param("greetingId") Long greetingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from Greeting g
             where g.roomId = :roomId
               and g.status = itda.greeting.domain.GreetingStatus.SENT
            """)
    List<Greeting> findSentByRoomIdForUpdate(@Param("roomId") Long roomId);

    @Query(value = """
            SELECT g.id AS greetingId,
                   g.room_id AS roomId,
                   r.pet_low_id AS petLowId,
                   r.pet_high_id AS petHighId
              FROM greetings g
              LEFT JOIN chat_rooms r ON r.id = g.room_id
             WHERE g.status = 'SENT'
               AND g.expires_at <= :now
             ORDER BY g.expires_at ASC, g.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<ExpiredGreetingCandidate> findExpiredSentCandidates(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    interface ExpiredGreetingCandidate {

        Long getGreetingId();

        Long getRoomId();

        Long getPetLowId();

        Long getPetHighId();
    }
}
