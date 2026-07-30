package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.Setlog;
import itda.setlog.dto.SetlogCreateRequest;
import itda.setlog.dto.SetlogCreateResponse;
import itda.setlog.repository.SetlogRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SetlogCreationService {

    private static final Set<MediaStatus> PLAYABLE_MEDIA_STATUSES =
            EnumSet.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final SetlogRepository setlogRepository;
    private final MediaRepository mediaRepository;
    private final PetRepository petRepository;
    private final ActivePetQueryService activePetQueryService;

    @Transactional
    public SetlogCreateResponse create(
            Long userId,
            SetlogCreateRequest request
    ) {
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(userId);
        Pet authorPet = petRepository.findById(activePet.petId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED)
                );
        Media media = mediaRepository
                .findByIdAndDeletedAtIsNull(request.mediaId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.MEDIA_NOT_FOUND)
                );

        if (!userId.equals(media.getUserId())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
        }
        if (media.getMediaType() != MediaType.VIDEO) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        if (!PLAYABLE_MEDIA_STATUSES.contains(media.getStatus())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
        }
        if (setlogRepository.existsByMedia_Id(media.getId())) {
            throw new BusinessException(
                    ErrorCode.SETLOG_MEDIA_ALREADY_USED
            );
        }

        Setlog setlog = setlogRepository.save(
                Setlog.createSeed(authorPet, media, request.caption())
        );
        return SetlogCreateResponse.from(setlog);
    }
}
