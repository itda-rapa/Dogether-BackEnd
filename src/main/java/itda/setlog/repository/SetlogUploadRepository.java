package itda.setlog.repository;

import itda.setlog.domain.SetlogUpload;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetlogUploadRepository extends JpaRepository<SetlogUpload, UUID> {

    Optional<SetlogUpload> findBySetlog_Id(Long setlogId);

    @Query(value = """
            select * from setlog_uploads
             where status in ('PRESIGNED', 'COMPLETED', 'EXPIRED', 'REJECTED', 'CANCELED')
               and expires_at <= :now
               and (
                   status <> 'COMPLETED'
                   or exists (
                       select 1 from media retained_media
                        where retained_media.id = setlog_uploads.media_id
                          and retained_media.object_version_id is not null
                          and btrim(retained_media.object_version_id) <> ''
                   )
               )
               and not exists (
                   select 1 from storage_delete_jobs job
                    where job.object_key = setlog_uploads.object_key
                      and job.reason = case setlog_uploads.status
                          when 'PRESIGNED' then 'UPLOAD_EXPIRED'
                          when 'COMPLETED' then 'UPLOAD_SURPLUS_VERSIONS'
                          when 'EXPIRED' then 'UPLOAD_EXPIRED'
                          when 'REJECTED' then 'UPLOAD_REJECTED'
                          when 'CANCELED' then 'UPLOAD_CANCELED'
                      end
               )
             order by expires_at, id
             for update skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<SetlogUpload> findCleanupCandidatesForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

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
