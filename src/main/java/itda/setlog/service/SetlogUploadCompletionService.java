package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.properties.S3Properties;
import itda.media.storage.ObjectMetadata;
import itda.media.storage.ObjectNotFoundException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedDownload;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.setlog.dto.SetlogAuthorPetResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.dto.SetlogSource;
import itda.setlog.service.SetlogUploadCompletionTransactionService.CompletionSnapshot;
import itda.setlog.service.SetlogUploadCompletionTransactionService.CompletionAttempt;
import itda.setlog.service.SetlogUploadCompletionTransactionService.PreparedUpload;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SetlogUploadCompletionService {

    private final SetlogUploadCompletionTransactionService transactions;
    private final ObjectStorage objectStorage;
    private final S3Properties s3Properties;
    private final PetDisplayQueryService petDisplayQueryService;

    public CompletionResult complete(
            Long ownerUserId,
            UUID uploadId,
            UUID requestId
    ) {
        if (ownerUserId == null || uploadId == null || requestId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Instant preparedAt = Instant.now();
        PreparedUpload prepared = transactions.prepare(
                ownerUserId, uploadId, requestId, preparedAt
        );
        throwIfFailed(prepared.failure());
        CompletionSnapshot completed;
        if (prepared.isReplay()) {
            completed = prepared.replay();
        } else {
            ObjectMetadata metadata = head(prepared.objectKey());
            CompletionAttempt attempt;
            try {
                attempt = transactions.finalizeUpload(
                        ownerUserId,
                        uploadId,
                        requestId,
                        metadata,
                        Instant.now()
                );
            } catch (DataIntegrityViolationException exception) {
                if (SetlogUploadCompletionConstraintTranslator
                        .isCompletionRace(exception)) {
                    throw new BusinessException(
                            ErrorCode.SETLOG_UPLOAD_STATE_CONFLICT
                    );
                }
                throw exception;
            }
            throwIfFailed(attempt.failure());
            completed = attempt.completed();
        }

        PresignedDownload download = presignDownload(completed);
        PetDisplaySummary authorPet =
                petDisplayQueryService.getPetDisplaySummary(completed.petId());
        SetlogResponse response = new SetlogResponse(
                completed.setlogId(),
                SetlogSource.USER,
                new SetlogAuthorPetResponse(
                        completed.petId(),
                        authorPet.publicTag(),
                        authorPet.nickname(),
                        authorPet.profileUrl(),
                        authorPet.verified(),
                        null
                ),
                download.url(),
                download.expiresAt(),
                null,
                0,
                0,
                List.of(),
                false,
                completed.createdAt()
        );
        return new CompletionResult(response, completed.replayed());
    }

    private ObjectMetadata head(String objectKey) {
        try {
            return objectStorage.head(objectKey);
        } catch (ObjectNotFoundException exception) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_OBJECT_NOT_FOUND
            );
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_STORAGE_UNAVAILABLE
            );
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_COMPLETE_STORAGE_REJECTED
            );
        }
    }

    private static void throwIfFailed(ErrorCode failure) {
        if (failure != null) {
            throw new BusinessException(failure);
        }
    }

    private PresignedDownload presignDownload(CompletionSnapshot completed) {
        try {
            return objectStorage.presignGet(
                    completed.objectKey(),
                    completed.versionId(),
                    Duration.ofSeconds(
                            s3Properties.presignedUrlExpirationSeconds()
                    )
            );
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_STORAGE_UNAVAILABLE
            );
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(
                    ErrorCode.SETLOG_UPLOAD_COMPLETE_STORAGE_REJECTED
            );
        }
    }

    public record CompletionResult(
            SetlogResponse response,
            boolean replayed
    ) {
    }
}
