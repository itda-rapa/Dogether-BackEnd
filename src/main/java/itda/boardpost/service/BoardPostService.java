package itda.boardpost.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.board.repository.BoardRepository;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.BoardPostMedia;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostCursorPage;
import itda.boardpost.dto.BoardPostFeedResponse;
import itda.boardpost.dto.BoardPostImageResponse;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.boardpost.repository.BoardPostMediaRepository;
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
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    public BoardPostService(
            BoardPostRepository posts,
            BoardPostMediaRepository postMedia,
            BoardRepository boards,
            UserRepository users,
            LockedActivePetCommandGuard actorGuard,
            PetDisplayQueryService petDisplays,
            BlockRelationshipQueryService blocks,
            MediaRepository media,
            MediaService mediaService
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
                images(links)
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
        if (!boards.existsById(boardId)) {
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
        List<BoardPostResponse> items = page.stream()
                .map(post -> BoardPostResponse.of(
                        post,
                        pets.get(post.getAuthorPetId()),
                        images(attachments.getOrDefault(post.getId(), List.of()))
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
        return BoardPostResponse.of(
                post,
                petDisplays.getPetDisplaySummary(post.getAuthorPetId()),
                images(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
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
        if (post.change(title, content)) {
            posts.flush();
        }
        return BoardPostResponse.of(
                post,
                petDisplays.getPetDisplaySummary(post.getAuthorPetId()),
                images(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
        );
    }

    @Transactional
    public void delete(Long userId, Long postId) {
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPost post = published(postId);
        requireAuthor(actor, post);
        post.delete(Instant.now());
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

    private List<BoardPostImageResponse> images(List<BoardPostMedia> links) {
        return links.stream()
                .map(link -> new BoardPostImageResponse(
                        link.getMediaId(),
                        mediaService.getPresignedDownloadUrl(link.getMediaId()).url(),
                        link.getDisplayOrder()
                ))
                .toList();
    }
}
