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
import java.util.List;
import java.util.Map;
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
        List<BoardPostComment> page = new ArrayList<>(comments.findVisibleByPostId(
                post.getId(),
                viewer.getId(),
                payload == null ? null : payload.createdAt(),
                payload == null ? null : payload.commentId(),
                size + 1
        ));
        boolean hasNext = page.size() > size;
        if (hasNext) {
            page = new ArrayList<>(page.subList(0, size));
        }

        Map<Long, PetDisplaySummary> authorPets = petDisplays.getPetDisplaySummaries(
                page.stream()
                        .map(BoardPostComment::getAuthorPetId)
                        .distinct()
                        .toList()
        );
        List<CommentResponse> items = page.stream()
                .map(comment -> CommentResponse.of(
                        comment,
                        authorPets.get(comment.getAuthorPetId())
                ))
                .toList();
        String nextCursor = hasNext && !page.isEmpty()
                ? CommentCursorCodec.encode(
                        page.getLast().getId(),
                        page.getLast().getCreatedAt()
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
        BoardPostComment comment = active(commentId);
        requireAuthor(actor, comment);
        comment.delete(Instant.now());
    }

    private BoardPostComment active(Long commentId) {
        return comments.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(this::commentNotFound);
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
