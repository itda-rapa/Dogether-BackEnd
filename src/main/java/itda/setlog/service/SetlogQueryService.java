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
import itda.setlog.dto.ShareableSetlogView;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Comparator;
import java.util.Collection;
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
        Map<Long, PresignedDownloadUrl> mediaUrls =
                mediaService.getPresignedDownloadUrls(
                        visibleSetlogs.stream()
                                .map(Setlog::getMedia)
                                .toList()
                );

        return visibleSetlogs.stream()
                .map(setlog -> toResponse(
                        setlog,
                        authorPets.get(setlog.getAuthorPet().getId()),
                        activePet,
                        relationships,
                        myReactions,
                        mediaUrls
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

    /**
     * Chat SETLOG_SHARE 전송용 공개 검증 계약. 표시 가능(VISIBLE + 재생 가능 Media + 정상 작성자)
     * setlog만 통과시키며, 그 판정은 {@link SetlogRepository#findInteractableById}가 가진
     * 단일 조건으로 수행한다. Chat은 setlog 내부 상태를 직접 해석하지 않는다.
     */
    public Setlog requireShareableSetlog(Long setlogId, Long ownerUserId) {
        Setlog setlog = setlogRepository
                .findInteractableById(setlogId, SetlogStatus.VISIBLE, PLAYABLE_MEDIA_STATUSES)
                .orElseThrow(() -> new BusinessException(ErrorCode.SETLOG_NOT_FOUND));
        if (ownerUserId == null || !ownerUserId.equals(setlog.getAuthorPet().getOwner().getId())) {
            throw new BusinessException(ErrorCode.SETLOG_SHARE_FORBIDDEN);
        }
        return setlog;
    }

    /**
     * 메시지 목록 hydration용 batch 계약. 표시 가능 setlog만 현재 요약을 반환하고,
     * 삭제·재생 불가·작성자 비정상 setlog는 {@code available=false}로 대체한다.
     */
    @Transactional(readOnly = true)
    public Map<Long, ShareableSetlogView> findShareableSetlogViews(Collection<Long> setlogIds) {
        return findShareableSetlogViews(setlogIds, null);
    }

    /**
     * 메시지 목록 hydration용 viewer-aware batch 계약. 조회자와 작성자 사이에 차단 관계가
     * 있으면 원본 Setlog가 아직 표시 가능하더라도 {@code available=false}로 숨긴다.
     * 차단 확인은 작성자별 개별 조회가 아니라 batch query 한 번으로 수행하며, 차단된
     * Setlog에는 media presigned URL을 발급하지 않는다.
     */
    @Transactional(readOnly = true)
    public Map<Long, ShareableSetlogView> findShareableSetlogViews(
            Collection<Long> setlogIds,
            Long viewerUserId
    ) {
        if (setlogIds == null || setlogIds.isEmpty()) {
            return Map.of();
        }
        List<Setlog> setlogs = setlogRepository.findAllByIdForShare(
                setlogIds, SetlogStatus.VISIBLE, PLAYABLE_MEDIA_STATUSES);
        Set<Long> blockedAuthorUserIds =
                blockRelationshipQueryService.findBlockedUserIdsBetween(
                        viewerUserId,
                        setlogs.stream()
                                .map(setlog -> setlog.getAuthorPet().getOwner().getId())
                                .collect(Collectors.toSet())
                );
        List<Setlog> visibleSetlogs = setlogs.stream()
                .filter(setlog -> !blockedAuthorUserIds.contains(
                        setlog.getAuthorPet().getOwner().getId()))
                .toList();
        Map<Long, MediaService.OwnedPresignedDownload> downloads = visibleSetlogs.isEmpty()
                ? Map.of()
                : mediaService.getMediaDownloadsByIds(
                        visibleSetlogs.stream().map(setlog -> setlog.getMedia().getId()).toList());
        Map<Long, ShareableSetlogView> result = new LinkedHashMap<>();
        for (Long setlogId : setlogIds) {
            result.put(setlogId, ShareableSetlogView.unavailable(setlogId));
        }
        for (Setlog setlog : visibleSetlogs) {
            result.put(setlog.getId(), toShareableView(
                    setlog, downloads.get(setlog.getMedia().getId())));
        }
        return Map.copyOf(result);
    }

    private ShareableSetlogView toShareableView(
            Setlog setlog,
            MediaService.OwnedPresignedDownload download
    ) {
        if (download == null) {
            return ShareableSetlogView.unavailable(setlog.getId());
        }
        return new ShareableSetlogView(
                setlog.getId(),
                true,
                setlog.getAuthorPet().getId(),
                setlog.getAuthorPet().getNickname(),
                setlog.getCaption(),
                download.media().getId(),
                download.media().getMediaType().name(),
                download.download().url(),
                download.download().expiresAt(),
                setlog.getCuteCount() + setlog.getLikeCount()
        );
    }
}
