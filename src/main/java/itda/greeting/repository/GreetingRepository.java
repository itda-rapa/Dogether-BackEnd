package itda.greeting.repository;

import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
