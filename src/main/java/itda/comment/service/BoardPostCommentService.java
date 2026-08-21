package itda.comment.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.comment.domain.BoardPostComment;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.dto.CommentCursorPage;
import itda.comment.dto.CommentListResponse;
import itda.comment.dto.CommentResponse;
import itda.comment.dto.CommentTreeResponse;
import itda.comment.dto.CommentUpdateRequest;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.support.CommentCursorCodec;
import itda.comment.support.CommentCursorCodec.CursorPayload;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardPostCommentService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final BoardPostCommentRepository comments;
    private final BoardPostRepository posts;
    private final UserRepository users;
    private final LockedActivePetCommandGuard actorGuard;
    private final PetDisplayQueryService petDisplays;
    private final BlockRelationshipQueryService blocks;

    public BoardPostCommentService(
            BoardPostCommentRepository comments,
            BoardPostRepository posts,
            UserRepository users,
            LockedActivePetCommandGuard actorGuard,
            PetDisplayQueryService petDisplays,
            BlockRelationshipQueryService blocks
    ) {
        this.comments = comments;
        this.posts = posts;
        this.users = users;
        this.actorGuard = actorGuard;
        this.petDisplays = petDisplays;
        this.blocks = blocks;
    }

    @Transactional
    public CommentResponse create(
            Long userId,
            Long postId,
            CommentCreateRequest request
    ) {
        validateContent(request.content());
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPost post = posts.findPublishedByIdForShare(postId)
                .orElseThrow(this::postNotFound);
        requireParentVisible(
                actor.userId(),
                actor.neighborhoodCode(),
                post
        );

        BoardPostComment comment = comments.save(BoardPostComment.create(
                post.getId(),
                actor.userId(),
                actor.petId(),
                request.content()
        ));
        return CommentResponse.of(
                comment,
                petDisplays.getPetDisplaySummary(actor.petId())
        );
    }

    @Transactional
    public CommentResponse createReply(
            Long userId,
            Long parentCommentId,
            CommentCreateRequest request
    ) {
        validateContent(request.content());
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);

        // This lookup supplies only the post ID needed for the lock order. The locked
        // lookup below is the authoritative parent state check.
        Long postId = comments.findById(parentCommentId)
                .map(BoardPostComment::getPostId)
                .orElseThrow(this::commentNotFound);
        BoardPost post = posts.findPublishedByIdForShare(postId)
                .orElseThrow(this::postNotFound);
        requireParentVisible(actor.userId(), actor.neighborhoodCode(), post);

        BoardPostComment parent = comments.findActiveByIdForShare(parentCommentId)
                .orElseThrow(this::commentNotFound);
        if (!post.getId().equals(parent.getPostId())) {
            throw commentNotFound();
        }

        List<BoardPostComment> path = hierarchyPath(parent);
        if (parent.getDepth() >= 3) {
            throw new BusinessException(ErrorCode.COMMENT_DEPTH_EXCEEDED);
        }
        if (path.stream().anyMatch(comment ->
                blocks.existsBlockBetween(actor.userId(), comment.getAuthorUserId()))) {
            throw commentNotFound();
        }

        BoardPostComment root = path.getFirst();
        BoardPostComment reply = comments.save(BoardPostComment.reply(
                post.getId(),
                actor.userId(),
                actor.petId(),
                request.content(),
                parent.getId(),
                root.getId(),
                (short) (parent.getDepth() + 1)
        ));
        return CommentResponse.of(
                reply,
                petDisplays.getPetDisplaySummary(actor.petId())
        );
    }

    @Transactional(readOnly = true)
    public CommentListResponse list(
            Long userId,
            Long postId,
            String cursor,
            Integer rawSize
    ) {
        User viewer = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)
                );
        BoardPost post = published(postId);
        requireParentVisible(
                viewer.getId(),
                viewer.getNeighborhoodCode(),
                post
        );

        int size = size(rawSize);
        CursorPayload payload = CommentCursorCodec.decode(cursor);
        List<BoardPostComment> roots = new ArrayList<>(comments.findVisibleByPostId(
                post.getId(),
                viewer.getId(),
                payload == null ? null : payload.createdAt(),
                payload == null ? null : payload.commentId(),
                size + 1
        ));
        boolean hasNext = roots.size() > size;
        if (hasNext) {
            roots = new ArrayList<>(roots.subList(0, size));
        }

        List<BoardPostComment> descendants = roots.isEmpty()
                ? List.of()
                : comments.findDescendantsByRootCommentIdIn(
                        post.getId(),
                        roots.stream().map(BoardPostComment::getId).toList()
                );
        List<BoardPostComment> allComments = new ArrayList<>(roots);
        allComments.addAll(descendants);
        Set<Long> blockedAuthorUserIds = blocks.findBlockedUserIdsBetween(
                viewer.getId(),
                allComments.stream()
                        .map(BoardPostComment::getAuthorUserId)
                        .collect(java.util.stream.Collectors.toSet())
        );
        Map<Long, List<BoardPostComment>> childrenByParentId = childrenByParentId(descendants);
        List<VisibleCommentNode> visibleRoots = roots.stream()
                .map(root -> toVisibleTree(
                        root,
                        childrenByParentId,
                        blockedAuthorUserIds
                ))
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<Long> visibleActivePetIds = new java.util.LinkedHashSet<>();
        visibleRoots.forEach(root -> collectVisibleActivePetIds(root, visibleActivePetIds));
        Map<Long, PetDisplaySummary> authorPets = petDisplays.getPetDisplaySummaries(
                visibleActivePetIds
        );
        List<CommentTreeResponse> items = visibleRoots.stream()
                .map(root -> toTree(root, authorPets))
                .toList();
        String nextCursor = hasNext && !roots.isEmpty()
                ? CommentCursorCodec.encode(
                        roots.getLast().getId(),
                        roots.getLast().getCreatedAt()
                )
                : null;
        return new CommentListResponse(
                items,
                new CommentCursorPage(nextCursor, hasNext)
        );
    }

    @Transactional
    public CommentResponse update(
            Long userId,
            Long commentId,
            CommentUpdateRequest request
    ) {
        validateContent(request.content());
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPostComment comment = active(commentId);
        published(comment.getPostId());
        requireAuthor(actor, comment);
        if (comment.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (comment.changeContent(request.content())) {
            comments.flush();
        }
        return CommentResponse.of(
                comment,
                petDisplays.getPetDisplaySummary(comment.getAuthorPetId())
        );
    }

    @Transactional
    public void delete(Long userId, Long commentId) {
        LockedActivePetCommandGuard.LockedActor actor = actorGuard.require(userId);
        BoardPostComment comment = comments.findActiveByIdForUpdate(commentId)
                .orElseThrow(this::commentNotFound);
        requireAuthor(actor, comment);
        comment.delete(Instant.now());
    }

    private BoardPostComment active(Long commentId) {
        return comments.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(this::commentNotFound);
    }

    private List<BoardPostComment> hierarchyPath(BoardPostComment parent) {
        List<BoardPostComment> reversed = new ArrayList<>();
        BoardPostComment current = parent;
        short expectedDepth = parent.getDepth();
        Long expectedRootCommentId = parent.getDepth() == 0
                ? parent.getId()
                : parent.getRootCommentId();
        while (true) {
            if (expectedDepth < 0 || expectedDepth > 3 || current.getDepth() != expectedDepth) {
                throw commentNotFound();
            }
            reversed.add(current);
            if (expectedDepth == 0) {
                if (current.getParentCommentId() != null
                        || current.getRootCommentId() != null
                        || !current.getId().equals(expectedRootCommentId)) {
                    throw commentNotFound();
                }
                break;
            }
            if (current.getParentCommentId() == null
                    || expectedRootCommentId == null
                    || !expectedRootCommentId.equals(current.getRootCommentId())) {
                throw commentNotFound();
            }
            BoardPostComment ancestor = comments.findById(current.getParentCommentId())
                    .orElseThrow(this::commentNotFound);
            if (!ancestor.getPostId().equals(parent.getPostId())) {
                throw commentNotFound();
            }
            current = ancestor;
            expectedDepth--;
        }
        if (!current.getId().equals(expectedRootCommentId)) {
            throw commentNotFound();
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private Map<Long, List<BoardPostComment>> childrenByParentId(
            List<BoardPostComment> descendants
    ) {
        Map<Long, List<BoardPostComment>> children = new HashMap<>();
        for (BoardPostComment descendant : descendants) {
            children.computeIfAbsent(descendant.getParentCommentId(), ignored -> new ArrayList<>())
                    .add(descendant);
        }
        Comparator<BoardPostComment> byCreatedAtAndId = Comparator
                .comparing(BoardPostComment::getCreatedAt)
                .thenComparing(BoardPostComment::getId);
        children.values().forEach(items -> items.sort(byCreatedAtAndId));
        return children;
    }

    private VisibleCommentNode toVisibleTree(
            BoardPostComment comment,
            Map<Long, List<BoardPostComment>> childrenByParentId,
            Set<Long> blockedAuthorUserIds
    ) {
        if (blockedAuthorUserIds.contains(comment.getAuthorUserId())) {
            return null;
        }
        List<VisibleCommentNode> replies = childrenByParentId
                .getOrDefault(comment.getId(), List.of())
                .stream()
                .map(child -> toVisibleTree(child, childrenByParentId, blockedAuthorUserIds))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (comment.getDeletedAt() != null) {
            if (replies.isEmpty()) {
                return null;
            }
        }
        return new VisibleCommentNode(comment, replies);
    }

    private void collectVisibleActivePetIds(
            VisibleCommentNode node,
            Set<Long> authorPetIds
    ) {
        if (node.comment().getDeletedAt() == null) {
            authorPetIds.add(node.comment().getAuthorPetId());
        }
        node.replies().forEach(reply -> collectVisibleActivePetIds(reply, authorPetIds));
    }

    private CommentTreeResponse toTree(
            VisibleCommentNode node,
            Map<Long, PetDisplaySummary> authorPets
    ) {
        BoardPostComment comment = node.comment();
        List<CommentTreeResponse> replies = node.replies().stream()
                .map(reply -> toTree(reply, authorPets))
                .toList();
        if (comment.getDeletedAt() != null) {
            return new CommentTreeResponse(
                    comment.getId(),
                    comment.getPostId(),
                    comment.getParentCommentId(),
                    comment.getDepth(),
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    replies
            );
        }
        return new CommentTreeResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getParentCommentId(),
                comment.getDepth(),
                false,
                itda.boardpost.dto.BoardPostAuthorPetResponse.from(
                        authorPets.get(comment.getAuthorPetId())
                ),
                comment.getContent(),
                comment.getVersion(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                replies
        );
    }

    private record VisibleCommentNode(
            BoardPostComment comment,
            List<VisibleCommentNode> replies
    ) {
    }

    private BoardPost published(Long postId) {
        return posts.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(this::postNotFound);
    }

    private void requireParentVisible(
            Long viewerUserId,
            String viewerNeighborhoodCode,
            BoardPost post
    ) {
        if ((!post.getAuthorUserId().equals(viewerUserId)
                && !post.getNeighborhoodCode().equals(viewerNeighborhoodCode))
                || blocks.existsBlockBetween(viewerUserId, post.getAuthorUserId())) {
            throw postNotFound();
        }
    }

    private void requireAuthor(
            LockedActivePetCommandGuard.LockedActor actor,
            BoardPostComment comment
    ) {
        if (!actor.userId().equals(comment.getAuthorUserId())
                || !actor.petId().equals(comment.getAuthorPetId())) {
            throw new BusinessException(ErrorCode.BOARD_POST_COMMENT_FORBIDDEN);
        }
    }

    private void validateContent(String content) {
        if (content == null
                || content.isBlank()
                || content.codePointCount(0, content.length()) > 5000) {
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

    private BusinessException postNotFound() {
        return new BusinessException(ErrorCode.BOARD_POST_NOT_FOUND);
    }

    private BusinessException commentNotFound() {
        return new BusinessException(ErrorCode.BOARD_POST_COMMENT_NOT_FOUND);
    }
}
