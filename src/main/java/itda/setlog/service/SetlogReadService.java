package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.MediaStatus;
import itda.media.service.MediaService;
import itda.media.service.MediaService.PresignedDownloadUrl;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.domain.ReactionType;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogReaction;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogAuthorPetResponse;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.dto.SetlogSource;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.support.SetlogCursorCodec;
import itda.setlog.support.SetlogCursorCodec.CursorPayload;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetlogReadService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_SIZE = 100;
    private static final List<MediaStatus> PLAYABLE_MEDIA_STATUSES =
            List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final SetlogRepository setlogRepository;
    private final SetlogReactionRepository setlogReactionRepository;
    private final UserRepository userRepository;
    private final ActivePetQueryService activePetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final FriendRelationshipQueryService friendRelationshipQueryService;
    private final MediaService mediaService;

    public SetlogReadService(
            SetlogRepository setlogRepository,
            SetlogReactionRepository setlogReactionRepository,
            UserRepository userRepository,
            ActivePetQueryService activePetQueryService,
            PetDisplayQueryService petDisplayQueryService,
            FriendRelationshipQueryService friendRelationshipQueryService,
            MediaService mediaService
    ) {
        this.setlogRepository = setlogRepository;
        this.setlogReactionRepository = setlogReactionRepository;
        this.userRepository = userRepository;
        this.activePetQueryService = activePetQueryService;
        this.petDisplayQueryService = petDisplayQueryService;
        this.friendRelationshipQueryService =
                friendRelationshipQueryService;
        this.mediaService = mediaService;
    }

    @Transactional(readOnly = true)
    public SetlogListResponse getSetlogs(
            Long userId,
            String cursor,
            Integer rawSize
    ) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)
                );
        int size = normalizeSize(rawSize);
        CursorPayload cursorPayload = SetlogCursorCodec.decode(cursor);
        ActivePetContext activePet = user.hasActivePet()
                ? activePetQueryService.requireActivePet(userId)
                : null;

        PageRequest pageRequest = PageRequest.of(0, size + 1);
        List<Setlog> candidates = cursorPayload == null
                ? setlogRepository.findVisibleFeedFirstPage(
                        userId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES,
                        PetStatus.ACTIVE,
                        AccountStatus.ACTIVE,
                        pageRequest
                )
                : setlogRepository.findVisibleFeedAfter(
                        userId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES,
                        PetStatus.ACTIVE,
                        AccountStatus.ACTIVE,
                        cursorPayload.createdAt(),
                        cursorPayload.setlogId(),
                        pageRequest
                );
        boolean hasNext = candidates.size() > size;
        List<Setlog> pageSetlogs = hasNext
                ? List.copyOf(candidates.subList(0, size))
                : List.copyOf(candidates);

        if (pageSetlogs.isEmpty()) {
            return new SetlogListResponse(
                    List.of(),
                    null,
                    false
            );
        }

        Map<Long, PetDisplaySummary> authorPets =
                petDisplayQueryService.getPetDisplaySummaries(
                        pageSetlogs.stream()
                                .map(setlog -> setlog.getAuthorPet().getId())
                                .toList()
                );
        Map<Long, FriendRelationship> relationships =
                getRelationships(activePet, pageSetlogs);
        Map<Long, List<ReactionType>> myReactions =
                getMyReactions(activePet, pageSetlogs);
        Map<Long, PresignedDownloadUrl> mediaUrls =
                mediaService.getPresignedDownloadUrls(
                        pageSetlogs.stream()
                                .map(Setlog::getMedia)
                                .toList()
                );

        List<SetlogResponse> items = pageSetlogs.stream()
                .map(setlog -> toResponse(
                        setlog,
                        authorPets.get(setlog.getAuthorPet().getId()),
                        activePet,
                        relationships,
                        myReactions,
                        mediaUrls
                ))
                .toList();
        Setlog lastSetlog = pageSetlogs.get(pageSetlogs.size() - 1);
        String nextCursor = hasNext
                ? SetlogCursorCodec.encode(
                        lastSetlog.getId(),
                        lastSetlog.getCreatedAt()
                )
                : null;
        return new SetlogListResponse(
                items,
                nextCursor,
                hasNext
        );
    }

    /**
     * 공유 카드의 상세 route 진입 시 피드와 같은 접근 정책을 다시 적용한다.
     * Media Presigned URL은 카드 값을 재사용하지 않고 이 조회에서 새로 발급한다.
     */
    @Transactional(readOnly = true)
    public SetlogResponse getSetlog(Long userId, Long setlogId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        ActivePetContext activePet = user.hasActivePet()
                ? activePetQueryService.requireActivePet(userId)
                : null;
        Setlog setlog = setlogRepository.findVisibleDetailById(
                        setlogId,
                        userId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES,
                        PetStatus.ACTIVE,
                        AccountStatus.ACTIVE
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.SETLOG_NOT_FOUND));
        Long authorPetId = setlog.getAuthorPet().getId();
        PetDisplaySummary authorPet = petDisplayQueryService
                .getPetDisplaySummaries(List.of(authorPetId))
                .get(authorPetId);
        if (authorPet == null) {
            throw new BusinessException(ErrorCode.SETLOG_NOT_FOUND);
        }
        Map<Long, FriendRelationship> relationships = getRelationships(
                activePet, List.of(setlog));
        Map<Long, List<ReactionType>> myReactions = getMyReactions(
                activePet, List.of(setlog));
        PresignedDownloadUrl mediaUrl = mediaService.getPresignedDownloadUrl(
                setlog.getMedia().getId());

        return toResponse(
                setlog,
                authorPet,
                activePet,
                relationships,
                myReactions,
                Map.of(setlog.getMedia().getId(), mediaUrl)
        );
    }

    private int normalizeSize(Integer rawSize) {
        if (rawSize == null) {
            return DEFAULT_LIMIT;
        }
        if (rawSize < 1 || rawSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return rawSize;
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
            Map<Long, List<ReactionType>> myReactions,
            Map<Long, PresignedDownloadUrl> mediaUrls
    ) {
        PresignedDownloadUrl mediaUrl = mediaUrls.get(
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
                setlog.isSeed() ? SetlogSource.SEED : SetlogSource.USER,
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
