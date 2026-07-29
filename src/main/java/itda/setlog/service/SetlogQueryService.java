package itda.setlog.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.MediaStatus;
import itda.media.service.MediaService;
import itda.media.service.MediaService.PresignedDownloadUrl;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.domain.ReactionType;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogReaction;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogAuthorPetResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetlogQueryService {

    private static final List<MediaStatus> PLAYABLE_MEDIA_STATUSES =
            List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final SetlogRepository setlogRepository;
    private final SetlogReactionRepository setlogReactionRepository;
    private final UserRepository userRepository;
    private final ActivePetQueryService activePetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final FriendRelationshipQueryService friendRelationshipQueryService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final MediaService mediaService;

    public SetlogQueryService(
            SetlogRepository setlogRepository,
            SetlogReactionRepository setlogReactionRepository,
            UserRepository userRepository,
            ActivePetQueryService activePetQueryService,
            PetDisplayQueryService petDisplayQueryService,
            FriendRelationshipQueryService friendRelationshipQueryService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            MediaService mediaService
    ) {
        this.setlogRepository = setlogRepository;
        this.setlogReactionRepository = setlogReactionRepository;
        this.userRepository = userRepository;
        this.activePetQueryService = activePetQueryService;
        this.petDisplayQueryService = petDisplayQueryService;
        this.friendRelationshipQueryService =
                friendRelationshipQueryService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.mediaService = mediaService;
    }

    /**
     * Returns the M1 seed Setlogs. A user without an Active Pet can view the
     * feed, but receives no relationship or reaction context.
     */
    @Transactional(readOnly = true)
    public List<SetlogResponse> getSeedSetlogs(Long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)
                );
        ActivePetContext activePet = user.hasActivePet()
                ? activePetQueryService.requireActivePet(userId)
                : null;

        List<Setlog> candidates = setlogRepository.findVisibleSeedSetlogs(
                SetlogStatus.VISIBLE,
                PLAYABLE_MEDIA_STATUSES
        );
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, PetDisplaySummary> authorPets =
                petDisplayQueryService.getPetDisplaySummaries(
                        candidates.stream()
                                .map(setlog ->
                                        setlog.getAuthorPet().getId()
                                )
                                .toList()
                );
        List<Setlog> visibleSetlogs = candidates.stream()
                .filter(setlog -> isVisibleTo(
                        userId,
                        authorPets.get(setlog.getAuthorPet().getId())
                ))
                .toList();
        if (visibleSetlogs.isEmpty()) {
            return List.of();
        }

        Map<Long, FriendRelationship> relationships =
                getRelationships(activePet, visibleSetlogs);
        Map<Long, List<ReactionType>> myReactions =
                getMyReactions(activePet, visibleSetlogs);

        return visibleSetlogs.stream()
                .map(setlog -> toResponse(
                        setlog,
                        authorPets.get(setlog.getAuthorPet().getId()),
                        activePet,
                        relationships,
                        myReactions
                ))
                .toList();
    }

    private boolean isVisibleTo(
            Long userId,
            PetDisplaySummary authorPet
    ) {
        return authorPet != null
                && !blockRelationshipQueryService.existsBlockBetween(
                        userId,
                        authorPet.ownerUserId()
                );
    }

    private Map<Long, FriendRelationship> getRelationships(
            ActivePetContext activePet,
            List<Setlog> setlogs
    ) {
        if (activePet == null) {
            return Map.of();
        }
        return friendRelationshipQueryService.getRelationships(
                activePet.petId(),
                setlogs.stream()
                        .map(setlog -> setlog.getAuthorPet().getId())
                        .toList()
        );
    }

    private Map<Long, List<ReactionType>> getMyReactions(
            ActivePetContext activePet,
            List<Setlog> setlogs
    ) {
        if (activePet == null) {
            return Map.of();
        }

        List<Long> setlogIds = setlogs.stream()
                .map(Setlog::getId)
                .toList();
        Map<Long, Set<ReactionType>> grouped = new LinkedHashMap<>();
        for (SetlogReaction reaction :
                setlogReactionRepository
                        .findAllBySetlog_IdInAndReactorPet_Id(
                                setlogIds,
                                activePet.petId()
                        )) {
            grouped.computeIfAbsent(
                    reaction.getSetlog().getId(),
                    ignored -> EnumSet.noneOf(ReactionType.class)
            ).add(reaction.getType());
        }

        return grouped.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .sorted(Comparator.comparingInt(Enum::ordinal))
                                .toList()
                ));
    }

    private SetlogResponse toResponse(
            Setlog setlog,
            PetDisplaySummary authorPet,
            ActivePetContext activePet,
            Map<Long, FriendRelationship> relationships,
            Map<Long, List<ReactionType>> myReactions
    ) {
        PresignedDownloadUrl mediaUrl =
                mediaService.getPresignedDownloadUrl(
                        setlog.getMedia().getId()
                );
        boolean canInteract = activePet != null
                && !activePet.ownerUserId().equals(authorPet.ownerUserId());
        FriendRelationship relationship = activePet == null
                ? null
                : relationships.getOrDefault(
                        authorPet.petId(),
                        FriendRelationship.NONE
                );

        return new SetlogResponse(
                setlog.getId(),
                new SetlogAuthorPetResponse(
                        authorPet.petId(),
                        authorPet.publicTag(),
                        authorPet.nickname(),
                        authorPet.profileUrl(),
                        authorPet.verified(),
                        relationship
                ),
                mediaUrl.url(),
                mediaUrl.expiresAt(),
                setlog.getCaption(),
                setlog.getCuteCount(),
                setlog.getLikeCount(),
                myReactions.getOrDefault(setlog.getId(), List.of()),
                canInteract,
                setlog.getCreatedAt()
        );
    }
}
