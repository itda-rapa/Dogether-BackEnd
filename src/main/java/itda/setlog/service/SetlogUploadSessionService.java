package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.storage.ObjectStorage;
import itda.media.storage.PresignedUpload;
import itda.media.storage.StorageProviderRejectedException;
import itda.media.storage.StorageProviderUnavailableException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.dto.SetlogUploadCreateRequest;
import itda.setlog.dto.SetlogUploadCreateResponse;
import itda.setlog.repository.SetlogUploadRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SetlogUploadSessionService {

    static final long MAX_UPLOAD_SIZE = 200L * 1024 * 1024;
    static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "video/mp4",
            "video/webm"
    );
    private static final Map<String, String> FILE_EXTENSIONS = Map.of(
            "video/mp4", "mp4",
            "video/webm", "webm"
    );

    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final SetlogUploadRepository setlogUploadRepository;
    private final ObjectStorage objectStorage;

    @Transactional
    public SetlogUploadCreateResponse create(
            Long userId,
            SetlogUploadCreateRequest request
    ) {
        validateRequest(request);

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (!user.hasActivePet()) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
        if (!user.isActivePet(request.petId())) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN);
        }

        Pet pet = petRepository.findByIdForUpdate(request.petId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN));
        if (!pet.belongsTo(userId) || !pet.isActive() || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_PET_FORBIDDEN);
        }

        UUID uploadId = UUID.randomUUID();
        String objectKey = objectKey(userId, pet.getId(), uploadId, request.contentType());
        PresignedUpload presigned;
        try {
            presigned = objectStorage.presignPut(
                    objectKey,
                    request.contentType(),
                    request.size(),
                    UPLOAD_TTL
            );
        } catch (StorageProviderUnavailableException exception) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_STORAGE_UNAVAILABLE);
        } catch (StorageProviderRejectedException exception) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_STORAGE_REJECTED);
        }

        setlogUploadRepository.save(SetlogUpload.presigned(
                uploadId,
                user,
                pet,
                objectKey,
                request.contentType(),
                request.size(),
                presigned.expiresAt()
        ));

        return SetlogUploadCreateResponse.from(uploadId, objectKey, presigned);
    }

    private static void validateRequest(SetlogUploadCreateRequest request) {
        if (request == null || request.petId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        validateFileName(request.fileName());
        if (request.size() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.size() <= 0) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_SIZE_INVALID);
        }
        if (request.size() > MAX_UPLOAD_SIZE) {
            throw new BusinessException(ErrorCode.UPLOAD_SIZE_EXCEEDED);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(request.contentType())) {
            throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_UNSUPPORTED);
        }
    }

    private static void validateFileName(String fileName) {
        if (fileName == null
                || fileName.isBlank()
                || fileName.length() > MAX_FILE_NAME_LENGTH
                || !fileName.equals(fileName.trim())
                || fileName.equals(".")
                || fileName.equals("..")
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.SETLOG_UPLOAD_FILE_NAME_INVALID);
        }
    }

    private static String objectKey(
            Long userId,
            Long petId,
            UUID uploadId,
            String contentType
    ) {
        String extension = FILE_EXTENSIONS.get(contentType.toLowerCase(Locale.ROOT));
        return "setlogs/%d/%d/%s.%s".formatted(userId, petId, uploadId, extension);
    }
}
