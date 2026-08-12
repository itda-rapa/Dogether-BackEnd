package itda.setlog.repository;

import itda.setlog.domain.SetlogUpload;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetlogUploadRepository extends JpaRepository<SetlogUpload, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select upload
              from SetlogUpload upload
              join fetch upload.owner
              join fetch upload.pet
             where upload.id = :uploadId
               and upload.owner.id = :ownerUserId
            """)
    Optional<SetlogUpload> findOwnedByIdForUpdate(
            @Param("uploadId") UUID uploadId,
            @Param("ownerUserId") Long ownerUserId
    );
}
