package itda.setlog.repository;

import itda.media.domain.MediaStatus;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlogRepository extends JpaRepository<Setlog, Long> {

    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner
              join fetch setlog.media media
             where setlog.seed = true
               and setlog.status = :status
               and media.status in :mediaStatuses
             order by setlog.createdAt asc, setlog.id asc
            """)
    List<Setlog> findVisibleSeedSetlogs(
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner
              join fetch setlog.media media
             where setlog.id = :setlogId
               and setlog.seed = true
               and setlog.status = :status
               and media.status in :mediaStatuses
            """)
    Optional<Setlog> findVisibleSeedByIdForUpdate(
            @Param("setlogId") Long setlogId,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses
    );

    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner
              join fetch setlog.media media
             where setlog.id = :setlogId
               and setlog.seed = true
               and setlog.status = :status
               and media.status in :mediaStatuses
            """)
    Optional<Setlog> findVisibleSeedById(
            @Param("setlogId") Long setlogId,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses
    );
}
