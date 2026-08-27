package itda.boardpost.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.board.repository.BoardRepository;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.BoardPostMedia;
import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostCursorPage;
import itda.boardpost.dto.BoardPostFeedResponse;
import itda.boardpost.dto.BoardPostImageResponse;
import itda.boardpost.dto.BoardPostReactionResponse;
import itda.boardpost.dto.BoardPostReactionSnapshot;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.boardpost.repository.BoardPostMediaRepository;
import itda.boardpost.repository.BoardPostReactionRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.support.BoardPostCursorCodec;
import itda.boardpost.support.BoardPostCursorCodec.CursorPayload;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.notification.domain.NotificationTargetType;
import itda.notification.domain.NotificationType;
import itda.notification.service.NotificationCommandService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardPostService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final BoardPostRepository posts;
    private final BoardPostMediaRepository postMedia;
    private final BoardRepository boards;
    private final UserRepository users;
    private final LockedActivePetCommandGuard actorGuard;
    private final PetDisplayQueryService petDisplays;
    private final BlockRelationshipQueryService blocks;
    private final MediaRepository media;
    private final MediaService mediaService;
    private final BoardPostReactionRepository reactions;
    private final BoardPostReactionQueryService reactionQueries;
    private final NotificationCommandService notificationCommandService;

    public BoardPostService(
            BoardPostRepository posts,
            BoardPostMediaRepository postMedia,
            BoardRepository boards,
            UserRepository users,
            LockedActivePetCommandGuard actorGuard,
            PetDisplayQueryService petDisplays,
            BlockRelationshipQueryService blocks,
            MediaRepository media,
            MediaService mediaService,
            BoardPostReactionRepository reactions,
            BoardPostReactionQueryService reactionQueries,
            NotificationCommandService notificationCommandService
    ) {
        this.posts = posts;
        this.postMedia = postMedia;
        this.boards = boards;
        this.users = users;
        this.actorGuard = actorGuard;
        this.petDisplays = petDisplays;
        this.blocks = blocks;
        this.media = media;
        this.mediaService = mediaService;
        this.reactions = reactions;
        this.reactionQueries = reactionQueries;
        this.notificationCommandService = notificationCommandService;
    }

    @Transactional
    public BoardPostResponse create(
            Long userId,
            Long boardId,
            BoardPostCreateRequest request
    ) {
        validateText(request.title(), request.content());
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        boards.findByIdForShare(boardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));
        List<Media> attachments = validAttachments(request.mediaIds(), actor.userId());
        BoardPost post = posts.save(BoardPost.publish(
                boardId,
                actor.userId(),
                actor.petId(),
                actor.neighborhoodCode(),
                request.title(),
                request.content()
        ));
        List<BoardPostMedia> links = new ArrayList<>(attachments.size());
        for (int index = 0; index < attachments.size(); index++) {
            links.add(BoardPostMedia.attach(post.getId(), attachments.get(index).getId(), index));
        }
        if (!links.isEmpty()) {
            postMedia.saveAll(links);
        }
        return BoardPostResponse.of(
                post,
                petDisplays.getPetDisplaySummary(actor.petId()),
                images(links, downloadUrlsForLoadedMedia(attachments)),
                BoardPostReactionSnapshot.none()
        );
    }

    @Transactional(readOnly = true)
    public BoardPostFeedResponse feed(
            Long userId,
            Long boardId,
            String cursor,
            Integer rawSize
    ) {
        User user = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)
                );
        if (!boards.existsByIdAndDeletedAtIsNull(boardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }
        int size = size(rawSize);
        CursorPayload payload = BoardPostCursorCodec.decode(cursor);
        List<BoardPost> page = new ArrayList<>(posts.findVisibleFeed(
                boardId,
                user.getNeighborhoodCode(),
                userId,
                payload == null ? null : payload.createdAt(),
                payload == null ? null : payload.postId(),
                size + 1
        ));
        boolean hasNext = page.size() > size;
        if (hasNext) {
            page = new ArrayList<>(page.subList(0, size));
        }
        Map<Long, PetDisplaySummary> pets = petDisplays.getPetDisplaySummaries(
                page.stream()
                        .map(BoardPost::getAuthorPetId)
                        .distinct()
                        .toList()
        );
        Map<Long, List<BoardPostMedia>> attachments = attachmentsByPostId(
                page.stream().map(BoardPost::getId).toList()
        );
        Map<Long, MediaService.PresignedDownloadUrl> attachmentDownloads =
                downloadUrlsForLinks(attachments.values().stream()
                        .flatMap(Collection::stream)
                        .toList());
        Map<Long, BoardPostReactionSnapshot> reactionStates = reactionStates(
                userId,
                page.stream().map(BoardPost::getId).toList()
        );
        List<BoardPostResponse> items = page.stream()
                .map(post -> BoardPostResponse.of(
                        post,
                        pets.get(post.getAuthorPetId()),
                        images(attachments.getOrDefault(post.getId(), List.of()), attachmentDownloads),
                        Objects.requireNonNull(
                                reactionStates.get(post.getId()),
                                "reaction snapshot must exist for every feed post"
                        )
                ))
                .toList();
        String next = hasNext && !page.isEmpty()
                ? BoardPostCursorCodec.encode(
                        page.getLast().getId(),
                        page.getLast().getCreatedAt()
                )
                : null;
        return new BoardPostFeedResponse(
                items,
                new BoardPostCursorPage(next, hasNext)
        );
    }

    @Transactional(readOnly = true)
    public BoardPostResponse detail(Long userId, Long postId) {
        User viewer = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)
                );
        BoardPost post = published(postId);
        if ((!post.getAuthorUserId().equals(userId)
                && !post.getNeighborhoodCode().equals(viewer.getNeighborhoodCode()))
                || blocks.existsBlockBetween(userId, post.getAuthorUserId())) {
            throw notFound();
        }
        List<BoardPostMedia> attachments = postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId());
        return BoardPostResponse.of(
                post,
                petDisplays.getPetDisplaySummary(post.getAuthorPetId()),
                images(attachments, downloadUrlsForLinks(attachments)),
                reactionState(userId, postId)
        );
    }

    @Transactional
    public BoardPostResponse update(
            Long userId,
            Long postId,
            BoardPostUpdateRequest request
    ) {
        if (request.titlePresent()) {
            validateTitle(request.title());
        }
        if (request.contentPresent()) {
            validateContent(request.content());
        }
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPost post = published(postId);
        requireAuthor(actor, post);
        if (post.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        String title = request.titlePresent() ? request.title() : post.getTitle();
        String content = request.contentPresent() ? request.content() : post.getContent();
        List<BoardPostMedia> existingLinks = request.mediaIdsPresent()
                ? postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId())
                : List.of();
        List<Media> attachments = request.mediaIdsPresent()
                ? validAttachments(request.mediaIds(), actor.userId())
                : List.of();
        boolean attachmentsChanged = request.mediaIdsPresent()
                && !sameOrderedMediaIds(existingLinks, attachments);
        boolean textChanged = post.change(title, content);
        if (attachmentsChanged) {
            post.markAttachmentsChanged();
        }
        if (textChanged || attachmentsChanged) {
            posts.flush();
        }
        List<BoardPostMedia> responseLinks;
        Map<Long, MediaService.PresignedDownloadUrl> attachmentDownloads;
        if (request.mediaIdsPresent()) {
            if (attachmentsChanged) {
                postMedia.deleteAll(existingLinks);
                postMedia.flush();
                responseLinks = links(post.getId(), attachments);
                if (!responseLinks.isEmpty()) {
                    postMedia.saveAll(responseLinks);
                }
            } else {
                responseLinks = existingLinks;
            }
            attachmentDownloads = downloadUrlsForLoadedMedia(attachments);
        } else {
            responseLinks = postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId());
            attachmentDownloads = downloadUrlsForLinks(responseLinks);
        }
        return BoardPostResponse.of(
                post,
                petDisplays.getPetDisplaySummary(post.getAuthorPetId()),
                images(responseLinks, attachmentDownloads),
                new BoardPostReactionSnapshot(
                        reactionCount(postId),
                        false,
                        reactions.countForPost(postId, BoardPostReactionType.HELPFUL.name()),
                        false
                )
        );
    }

    @Transactional
    public void delete(Long userId, Long postId) {
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPost post = posts.findPublishedByIdForUpdate(postId)
                .orElseThrow(this::notFound);
        requireAuthor(actor, post);
        post.delete(Instant.now());
    }

    @Transactional
    public BoardPostReactionResponse addReaction(
            Long userId,
            Long postId,
            BoardPostReactionType type
    ) {
        ReactionTarget target = reactionTarget(userId, postId);
        if (reactions.insertIgnore(postId, target.actor().petId(), type.name()) == 1) {
            notificationCommandService.notifyReaction(target.post().getAuthorPetId(), target.actor().petId(),
                    target.actor().nickname(), target.actor().profileAssetId(),
                    notificationType(type), NotificationTargetType.BOARD_POST, postId, postId, null);
        }
        return reactionResponse(postId, type, true);
    }

    @Transactional
    public BoardPostReactionResponse removeReaction(
            Long userId,
            Long postId,
            BoardPostReactionType type
    ) {
        ReactionTarget target = reactionTarget(userId, postId);
        reactions.deleteReaction(postId, target.actor().petId(), type.name());
        return reactionResponse(postId, type, false);
    }

    private ReactionTarget reactionTarget(
            Long userId,
            Long postId
    ) {
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPost post = posts.findPublishedByIdForShare(postId)
                .orElseThrow(this::notFound);
        if ((!post.getAuthorUserId().equals(actor.userId())
                && !post.getNeighborhoodCode().equals(actor.neighborhoodCode()))
                || blocks.existsBlockBetween(actor.userId(), post.getAuthorUserId())) {
            throw notFound();
        }
        if (post.getAuthorUserId().equals(actor.userId())) {
            throw new BusinessException(ErrorCode.BOARD_POST_SELF_REACTION_FORBIDDEN);
        }
        return new ReactionTarget(actor, post);
    }

    private NotificationType notificationType(BoardPostReactionType type) {
        return switch (type) {
            case LIKE -> NotificationType.BOARD_POST_LIKE;
            case HELPFUL -> NotificationType.BOARD_POST_HELPFUL;
        };
    }

    private BoardPostReactionResponse reactionResponse(
            Long postId,
            BoardPostReactionType type,
            boolean reacted
    ) {
        return new BoardPostReactionResponse(
                postId,
                type,
                reacted,
                reactions.countForPost(postId, type.name())
        );
    }

    private BoardPost published(Long id) {
        return posts.findByIdAndStatus(id, PostStatus.PUBLISHED)
                .orElseThrow(this::notFound);
    }

    private void requireAuthor(
            LockedActivePetCommandGuard.LockedActor actor,
            BoardPost post
    ) {
        if (!actor.userId().equals(post.getAuthorUserId())
                || !actor.petId().equals(post.getAuthorPetId())) {
            throw new BusinessException(ErrorCode.BOARD_POST_FORBIDDEN);
        }
    }

    private void validateText(String title, String content) {
        validateTitle(title);
        validateContent(content);
    }

    private void validateTitle(String text) {
        if (text == null
                || text.isBlank()
                || text.codePointCount(0, text.length()) > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateContent(String text) {
        if (text == null
                || text.isBlank()
                || text.codePointCount(0, text.length()) > 5000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private int size(Integer value) {
        int size = value == null ? DEFAULT_SIZE : value;
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return size;
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.BOARD_POST_NOT_FOUND);
    }

    private record ReactionTarget(LockedActivePetCommandGuard.LockedActor actor, BoardPost post) {
    }

    private BoardPostReactionSnapshot reactionState(Long userId, Long postId) {
        return reactionQueries.findForPost(userId, postId);
    }

    private Map<Long, BoardPostReactionSnapshot> reactionStates(
            Long userId,
            Collection<Long> postIds
    ) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return reactionQueries.findForPosts(userId, postIds);
    }

    private long reactionCount(Long postId) {
        return reactionQueries.countForPost(postId);
    }

    private List<Media> validAttachments(List<Long> mediaIds, Long userId) {
        if (mediaIds == null || mediaIds.size() > 5) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Long> unique = new HashSet<>();
        for (Long mediaId : mediaIds) {
            if (mediaId == null || mediaId <= 0 || !unique.add(mediaId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        if (mediaIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Media> byId = new HashMap<>();
        for (Media attachment : media.findAllById(mediaIds)) {
            byId.put(attachment.getId(), attachment);
        }
        List<Media> attachments = new ArrayList<>(mediaIds.size());
        for (Long mediaId : mediaIds) {
            Media attachment = byId.get(mediaId);
            if (attachment == null || attachment.getDeletedAt() != null) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
            }
            if (!userId.equals(attachment.getUserId())) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
            }
            if (attachment.getMediaType() != MediaType.IMAGE) {
                throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
            }
            if (attachment.getStatus() != MediaStatus.UPLOADED
                    && attachment.getStatus() != MediaStatus.COMPLETED) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
            }
            attachments.add(attachment);
        }
        return attachments;
    }

    private Map<Long, List<BoardPostMedia>> attachmentsByPostId(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<BoardPostMedia>> byPostId = new HashMap<>();
        for (BoardPostMedia attachment : postMedia.findByPostIdIn(postIds)) {
            byPostId.computeIfAbsent(attachment.getPostId(), ignored -> new ArrayList<>())
                    .add(attachment);
        }
        byPostId.values().forEach(links -> links.sort(
                java.util.Comparator.comparingInt(BoardPostMedia::getDisplayOrder)
        ));
        return byPostId;
    }

    private boolean sameOrderedMediaIds(
            List<BoardPostMedia> links,
            List<Media> attachments
    ) {
        if (links.size() != attachments.size()) {
            return false;
        }
        for (int index = 0; index < links.size(); index++) {
            if (!Objects.equals(links.get(index).getMediaId(), attachments.get(index).getId())) {
                return false;
            }
        }
        return true;
    }

    private List<BoardPostMedia> links(Long postId, List<Media> attachments) {
        List<BoardPostMedia> links = new ArrayList<>(attachments.size());
        for (int index = 0; index < attachments.size(); index++) {
            links.add(BoardPostMedia.attach(postId, attachments.get(index).getId(), index));
        }
        return links;
    }

    private Map<Long, MediaService.PresignedDownloadUrl> downloadUrlsForLinks(
            Collection<BoardPostMedia> links
    ) {
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> mediaIds = new LinkedHashSet<>();
        for (BoardPostMedia link : links) {
            mediaIds.add(link.getMediaId());
        }
        List<Long> orderedMediaIds = new ArrayList<>(mediaIds);
        Map<Long, Media> byId = new HashMap<>();
        for (Media loaded : media.findAllById(orderedMediaIds)) {
            byId.put(loaded.getId(), loaded);
        }
        List<Media> loaded = new ArrayList<>(mediaIds.size());
        for (Long mediaId : mediaIds) {
            Media mediaItem = byId.get(mediaId);
            if (mediaItem == null) {
                throw new IllegalArgumentException("Media not found: " + mediaId);
            }
            loaded.add(mediaItem);
        }
        return downloadUrlsForLoadedMedia(loaded);
    }

    private Map<Long, MediaService.PresignedDownloadUrl> downloadUrlsForLoadedMedia(
            Collection<Media> mediaItems
    ) {
        if (mediaItems.isEmpty()) {
            return Map.of();
        }
        return mediaService.getPresignedDownloadUrls(mediaItems);
    }

    private List<BoardPostImageResponse> images(
            List<BoardPostMedia> links,
            Map<Long, MediaService.PresignedDownloadUrl> downloads
    ) {
        return links.stream()
                .map(link -> new BoardPostImageResponse(
                        link.getMediaId(),
                        Objects.requireNonNull(downloads.get(link.getMediaId())).url(),
                        link.getDisplayOrder()
                ))
                .toList();
    }
}
