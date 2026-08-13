package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.repository.MediaRepository;
import itda.media.storage.ObjectMetadata;
import itda.pet.domain.Pet;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.repository.SetlogUploadRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SetlogUploadCompletionTransactionService {

    private final SetlogUploadRepository uploadRepository;
    private final MediaRepository mediaRepository;
    private final SetlogRepository setlogRepository;
    private final SetlogUploadProperties uploadProperties;

    @Transactional
    public PreparedUpload prepare(
            Long ownerUserId,
            UUID uploadId,
            UUID requestId,
            Instant now
    ) {
        SetlogUpload upload = findOwnedForUpdate(ownerUserId, uploadId);
        CompletionSnapshot replay = completedReplay(upload, requestId);
        if (replay != null) {
            return PreparedUpload.replayed(replay);
        }
        requirePresigned(upload);
        if (upload.isExpiredAt(now)) {
            upload.expire();
            return PreparedUpload.failed(ErrorCode.SETLOG_UPLOAD_EXPIRED);
        }
        requireEligibleOwnerAndPet(upload);
        return PreparedUpload.pending(
                upload.getObjectKey(),
                upload.getExpectedSize(),
                upload.getContentType()
        );
    }

    @Transactional
    public CompletionAttempt finalizeUpload(
            Long ownerUserId,
            UUID uploadId,
            UUID requestId,
            String caption,
            ObjectMetadata metadata,
            Instant now
    ) {
        SetlogUpload upload = findOwnedForUpdate(ownerUserId, uploadId);
        CompletionSnapshot replay = completedReplay(upload, requestId);
        if (replay != null) {
            return CompletionAttempt.completed(replay.asReplay());
        }
        requirePresigned(upload);
        if (upload.isExpiredAt(now)) {
            upload.expire();
            return CompletionAttempt.failed(ErrorCode.SETLOG_UPLOAD_EXPIRED);
        }
        requireEligibleOwnerAndPet(upload);
        if (uploadProperties.requireVersionId()
                && normalizeVersionId(metadata == null ? null : metadata.versionId()) == null) {
            return CompletionAttempt.failed(
                    ErrorCode.SETLOG_UPLOAD_VERSIONING_UNAVAILABLE
            );
        }
        if (!isValidMetadata(upload, metadata)) {
            upload.reject();
            return CompletionAttempt.failed(
                    ErrorCode.SETLOG_UPLOAD_METADATA_MISMATCH
            );
        }

        Media media = mediaRepository.saveAndFlush(Media.verifiedVideo(
                upload.getObjectKey(),
                ownerUserId,
                metadata.size(),
                metadata.contentType(),
                metadata.etag(),
                normalizeVersionId(metadata.versionId()),
                metadata.lastModified(),
                now
        ));
        Setlog setlog = setlogRepository.saveAndFlush(
                Setlog.createUser(upload.getPet(), media, caption)
        );
        upload.complete(requestId, media, setlog, now);
        uploadRepository.saveAndFlush(upload);
        return CompletionAttempt.completed(snapshot(upload, false));
    }

    private static boolean isValidMetadata(
            SetlogUpload upload,
            ObjectMetadata metadata
    ) {
        if (metadata == null
                || metadata.size() <= 0
                || metadata.contentType() == null
                || metadata.etag() == null
                || metadata.etag().isBlank()
                || metadata.lastModified() == null) {
            return false;
        }
        return metadata.size() == upload.getExpectedSize()
                && Objects.equals(
                        metadata.contentType(),
                        upload.getContentType()
                );
    }

    private static String normalizeVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()
                || "null".equalsIgnoreCase(versionId.trim())) {
            return null;
        }
        return versionId;
    }

    private SetlogUpload findOwnedForUpdate(Long ownerUserId, UUID uploadId) {
        return uploadRepository.findOwnedByIdForUpdate(uploadId, ownerUserId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_UPLOAD_NOT_FOUND)
                );
    }

    private static void requirePresigned(SetlogUpload upload) {
        if (upload.getStatus() != SetlogUploadStatus.PRESIGNED) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_STATE_CONFLICT);
        }
    }

    private static void requireEligibleOwnerAndPet(SetlogUpload upload) {
        Pet pet = upload.getPet();
        if (!upload.getOwner().isActive()
                || !upload.getOwner().isActivePet(pet.getId())
                || !pet.belongsTo(upload.getOwner().getId())
                || !pet.isActive()
                || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN);
        }
    }

    private static CompletionSnapshot completedReplay(
            SetlogUpload upload,
            UUID requestId
    ) {
        if (upload.getStatus() != SetlogUploadStatus.COMPLETED) {
            return null;
        }
        if (!Objects.equals(upload.getCompletionRequestId(), requestId)) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_IDEMPOTENCY_CONFLICT
            );
        }
        return snapshot(upload, true);
    }

    private static CompletionSnapshot snapshot(
            SetlogUpload upload,
            boolean replayed
    ) {
        Setlog setlog = upload.getSetlog();
        Media media = upload.getMedia();
        Pet pet = upload.getPet();
        if (setlog == null || media == null) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_STATE_CONFLICT);
        }
        return new CompletionSnapshot(
                setlog.getId(),
                pet.getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                setlog.getCaption(),
                media.getPath(),
                media.getObjectVersionId(),
                setlog.getCreatedAt(),
                replayed
        );
    }

    public record PreparedUpload(
            String objectKey,
            long expectedSize,
            String contentType,
            CompletionSnapshot replay,
            ErrorCode failure
    ) {
        static PreparedUpload pending(
                String objectKey,
                long expectedSize,
                String contentType
        ) {
            return new PreparedUpload(
                    objectKey, expectedSize, contentType, null, null
            );
        }

        static PreparedUpload replayed(CompletionSnapshot replay) {
            return new PreparedUpload(null, 0, null, replay, null);
        }

        static PreparedUpload failed(ErrorCode errorCode) {
            return new PreparedUpload(null, 0, null, null, errorCode);
        }

        public boolean isReplay() {
            return replay != null;
        }
    }

    public record CompletionAttempt(
            CompletionSnapshot completed,
            ErrorCode failure
    ) {
        static CompletionAttempt completed(CompletionSnapshot completed) {
            return new CompletionAttempt(completed, null);
        }

        static CompletionAttempt failed(ErrorCode failure) {
            return new CompletionAttempt(null, failure);
        }
    }

    public record CompletionSnapshot(
            Long setlogId,
            Long petId,
            String petPublicTag,
            String petNickname,
            String caption,
            String objectKey,
            String versionId,
            Instant createdAt,
            boolean replayed
    ) {
        CompletionSnapshot asReplay() {
            return new CompletionSnapshot(
                    setlogId,
                    petId,
                    petPublicTag,
                    petNickname,
                    caption,
                    objectKey,
                    versionId,
                    createdAt,
                    true
            );
        }
    }
}
