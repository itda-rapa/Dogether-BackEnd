package itda.setlog.repository;

import itda.media.domain.MediaStatus;
import itda.pet.domain.PetStatus;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.user.domain.AccountStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlogRepository extends JpaRepository<Setlog, Long> {

    @Query("select setlog.authorPet.id from Setlog setlog where setlog.id = :setlogId")
    Optional<Long> findAuthorPetIdById(@Param("setlogId") Long setlogId);

    /**
     * Chat SETLOG_SHARE hydration용 batch 조회. 표시 가능(VISIBLE + 재생 가능 Media + 정상 작성자)
     * setlog만 반환하므로, 누락된 id는 "접근 불가"로 처리하면 된다.
     */
    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner
              join fetch setlog.media media
             where setlog.id in :setlogIds
               and setlog.status = :status
               and media.status in :mediaStatuses
               and media.deletedAt is null
               and authorPet.status = itda.pet.domain.PetStatus.ACTIVE
               and authorPet.deletedAt is null
               and authorPet.owner.accountStatus = itda.user.domain.AccountStatus.ACTIVE
            """)
    List<Setlog> findAllByIdForShare(
            @Param("setlogIds") Collection<Long> setlogIds,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select setlog from Setlog setlog
              join fetch setlog.authorPet pet
              join fetch pet.owner owner
              join fetch setlog.media media
             where setlog.id = :setlogId
            """)
    Optional<Setlog> findByIdForDelete(@Param("setlogId") Long setlogId);

    boolean existsByMedia_Id(Long mediaId);

    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner author
              join fetch setlog.media media
             where setlog.status = :status
               and media.status in :mediaStatuses
               and media.deletedAt is null
               and authorPet.status = :petStatus
               and authorPet.deletedAt is null
               and author.accountStatus = :accountStatus
               and not exists (
                   select userBlock.id
                     from UserBlock userBlock
                    where userBlock.blockerUserId = :viewerUserId
                      and userBlock.blockedUserId = author.id
               )
               and not exists (
                   select userBlock.id
                     from UserBlock userBlock
                    where userBlock.blockerUserId = author.id
                      and userBlock.blockedUserId = :viewerUserId
               )
             order by setlog.createdAt desc, setlog.id desc
            """)
    List<Setlog> findVisibleFeedFirstPage(
            @Param("viewerUserId") Long viewerUserId,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses,
            @Param("petStatus") PetStatus petStatus,
            @Param("accountStatus") AccountStatus accountStatus,
            Pageable pageable
    );

    @Query("""
            select setlog
              from Setlog setlog
              join fetch setlog.authorPet authorPet
              join fetch authorPet.owner author
              join fetch setlog.media media
             where setlog.status = :status
               and media.status in :mediaStatuses
               and media.deletedAt is null
               and authorPet.status = :petStatus
               and authorPet.deletedAt is null
               and author.accountStatus = :accountStatus
               and not exists (
                   select userBlock.id
                     from UserBlock userBlock
                    where userBlock.blockerUserId = :viewerUserId
                      and userBlock.blockedUserId = author.id
               )
               and not exists (
                   select userBlock.id
                     from UserBlock userBlock
                    where userBlock.blockerUserId = author.id
                      and userBlock.blockedUserId = :viewerUserId
               )
               and (
                   setlog.createdAt < :cursorCreatedAt
                   or (
                       setlog.createdAt = :cursorCreatedAt
                       and setlog.id < :cursorSetlogId
                   )
               )
             order by setlog.createdAt desc, setlog.id desc
            """)
    List<Setlog> findVisibleFeedAfter(
            @Param("viewerUserId") Long viewerUserId,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses,
            @Param("petStatus") PetStatus petStatus,
            @Param("accountStatus") AccountStatus accountStatus,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorSetlogId") Long cursorSetlogId,
            Pageable pageable
    );

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
               and setlog.status = :status
               and media.status in :mediaStatuses
               and media.deletedAt is null
               and authorPet.status = itda.pet.domain.PetStatus.ACTIVE
               and authorPet.deletedAt is null
               and authorPet.owner.accountStatus = itda.user.domain.AccountStatus.ACTIVE
            """)
    Optional<Setlog> findInteractableByIdForUpdate(
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
               and setlog.status = :status
               and media.status in :mediaStatuses
               and media.deletedAt is null
               and authorPet.status = itda.pet.domain.PetStatus.ACTIVE
               and authorPet.deletedAt is null
               and authorPet.owner.accountStatus = itda.user.domain.AccountStatus.ACTIVE
            """)
    Optional<Setlog> findInteractableById(
            @Param("setlogId") Long setlogId,
            @Param("status") SetlogStatus status,
            @Param("mediaStatuses") List<MediaStatus> mediaStatuses
    );
}
