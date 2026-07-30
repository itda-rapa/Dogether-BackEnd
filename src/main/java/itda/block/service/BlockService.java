package itda.block.service;

import itda.block.domain.UserBlock;
import itda.block.dto.BlockCreateRequest;
import itda.block.dto.response.BlockListResponse;
import itda.block.dto.response.BlockResponse;
import itda.block.repository.UserBlockRepository;
import itda.block.support.BlockCursorCodec;
import itda.block.support.BlockCursorCodec.CursorPayload;
import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.service.FriendBlockCleanupService;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final ActivePetQueryService activePetQueryService;
    private final InteractionPairLockService interactionPairLockService;
    private final FriendBlockCleanupService friendBlockCleanupService;

    public BlockService(
            UserBlockRepository userBlockRepository,
            UserRepository userRepository,
            ActivePetQueryService activePetQueryService,
            InteractionPairLockService interactionPairLockService,
            FriendBlockCleanupService friendBlockCleanupService
    ) {
        this.userBlockRepository = userBlockRepository;
        this.userRepository = userRepository;
        this.activePetQueryService = activePetQueryService;
        this.interactionPairLockService = interactionPairLockService;
        this.friendBlockCleanupService = friendBlockCleanupService;
    }

    /**
     * Block a pet owner identified by {@code targetPetId}.
     *
     * <ul>
     *   <li>If the caller already blocked the target owner, returns 200 + existing block.</li>
     *   <li>If a new block is created, returns 201 for the new block.</li>
     *   <li>Rejects self-block and same-owner interaction.</li>
     * </ul>
     */
    @Transactional
    public BlockResult block(Long userId, BlockCreateRequest request) {
        ActivePetContext callerPet = activePetQueryService.requireActivePet(userId);
        InteractionPairContext pair = interactionPairLockService.lockInteractionPair(
                callerPet.petId(),
                request.targetPetId()
        );

        validateSourceState(userId, callerPet, pair);

        Long targetOwnerId = pair.targetUser().userId();
        if (pair.sourcePet().ownerUserId().equals(targetOwnerId)) {
            throw new BusinessException(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }

        int inserted = userBlockRepository.insertOnConflict(
                pair.sourceUser().userId(),
                targetOwnerId,
                pair.sourcePet().petId(),
                pair.targetPet().petId()
        );
        UserBlock stored = userBlockRepository
                .findByBlockerUserIdAndBlockedUserId(pair.sourceUser().userId(), targetOwnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        BlockResponse response = toBlockResponse(stored, pair.targetUser().publicTag());

        friendBlockCleanupService.cleanupBetweenUsers(
                pair.sourceUser().userId(),
                targetOwnerId
        );

        return new BlockResult(response, inserted == 1);
    }

    private void validateSourceState(
            Long userId,
            ActivePetContext callerPet,
            InteractionPairContext pair
    ) {
        LockedUserContext sourceUser = pair.sourceUser();
        LockedPetContext sourcePet = pair.sourcePet();
        if (!Objects.equals(userId, sourceUser.userId())
                || !Objects.equals(callerPet.ownerUserId(), sourceUser.userId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (sourceUser.accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(sourceUser.activePetId(), callerPet.petId())
                || sourcePet.status() != PetStatus.ACTIVE
                || sourcePet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }

    /**
     * List blocks for the current user, newest first via (createdAt DESC, id DESC) cursor.
     *
     * <p>A block is User-level safety data, so this does not require an Active Pet. The caller
     * must still be able to see who they blocked while they have no Pet, or while their Pet is
     * suspended. Only {@link #block} requires an Active Pet, because blocking happens from an
     * interaction context.
     */
    @Transactional(readOnly = true)
    public BlockListResponse listBlocks(Long userId, String cursor, Integer rawLimit) {
        int limit = validateLimit(rawLimit);
        CursorPayload payload = BlockCursorCodec.decode(cursor);

        List<UserBlock> blocks = userBlockRepository.findBlocksByBlocker(
                userId,
                payload != null ? payload.createdAt() : null,
                payload != null ? payload.blockId() : null,
                PageRequest.of(0, limit + 1) // fetch one extra to detect hasNext
        );

        boolean hasNext = blocks.size() > limit;
        if (hasNext) {
            blocks = blocks.subList(0, limit);
        }

        Map<Long, User> blockedUsers = new HashMap<>();
        userRepository.findAllById(
                blocks.stream().map(UserBlock::getBlockedUserId).toList()
        ).forEach(user -> blockedUsers.put(user.getId(), user));

        List<BlockResponse> items = blocks.stream()
                .map(block -> {
                    User blockedUser = blockedUsers.get(block.getBlockedUserId());
                    if (blockedUser == null) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
                    }
                    return toBlockResponse(block, blockedUser.getPublicTag());
                })
                .toList();

        String nextCursor = hasNext && !blocks.isEmpty()
                ? BlockCursorCodec.encode(blocks.get(blocks.size() - 1).getId(),
                        blocks.get(blocks.size() - 1).getCreatedAt())
                : null;

        return new BlockListResponse(items, new CursorPage(nextCursor, hasNext));
    }

    private int validateLimit(Integer raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (raw < 1 || raw > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return raw;
    }

    private BlockResponse toBlockResponse(UserBlock block, String blockedUserPublicTag) {
        return new BlockResponse(
                block.getId(),
                block.getBlockedUserId(),
                blockedUserPublicTag,
                block.getCreatedAt()
        );
    }

    /**
     * Result of a block operation, containing the block entity and whether it is newly created.
     */
    public record BlockResult(BlockResponse block, boolean created) {}
}
