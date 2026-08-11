package itda.setlog.repository;

import itda.setlog.domain.ReactionType;
import itda.setlog.domain.SetlogReaction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetlogReactionRepository
        extends JpaRepository<SetlogReaction, Long> {

    List<SetlogReaction> findAllBySetlog_IdInAndReactorPet_Id(
            Collection<Long> setlogIds,
            Long reactorPetId
    );

    Optional<SetlogReaction> findBySetlog_IdAndReactorPet_IdAndType(
            Long setlogId,
            Long reactorPetId,
            ReactionType type
    );
}
