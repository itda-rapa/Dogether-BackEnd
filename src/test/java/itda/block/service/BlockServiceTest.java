package itda.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.block.domain.UserBlock;
import itda.block.dto.BlockCreateRequest;
import itda.block.repository.UserBlockRepository;
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
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    private static final long USER_ID = 1L;
    private static final long SOURCE_PET_ID = 11L;
    private static final long TARGET_USER_ID = 2L;
    private static final long TARGET_PET_ID = 22L;
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");

    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private FriendBlockCleanupService friendBlockCleanupService;

    @InjectMocks
    private BlockService blockService;

    @Test
    void createsBlockAfterLockAndCleansEveryFriendRelation() {
        UserBlock block = stubSuccessfulBlock(validPair(), 1);

        BlockService.BlockResult result =
                blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID));

        assertThat(result.created()).isTrue();
        assertThat(result.block().blockId()).isEqualTo(100L);
        assertThat(result.block().blockedUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(result.block().blockedUserPublicTag()).isEqualTo("target#0002");
        assertThat(result.block().createdAt()).isEqualTo(CREATED_AT);

        InOrder order = inOrder(
                interactionPairLockService,
                userBlockRepository,
                block,
                friendBlockCleanupService
        );
        order.verify(interactionPairLockService)
                .lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID);
        order.verify(userBlockRepository).insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID);
        order.verify(userBlockRepository)
                .findByBlockerUserIdAndBlockedUserId(USER_ID, TARGET_USER_ID);
        order.verify(block).getId();
        order.verify(block).getBlockedUserId();
        order.verify(block).getCreatedAt();
        order.verify(friendBlockCleanupService).cleanupBetweenUsers(USER_ID, TARGET_USER_ID);
    }

    @Test
    void existingBlockUsesTheSameLockAndRepairsRelationsIdempotently() {
        stubSuccessfulBlock(validPair(), 0);

        BlockService.BlockResult result =
                blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID));

        assertThat(result.created()).isFalse();
        verify(interactionPairLockService)
                .lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID);
        verify(friendBlockCleanupService).cleanupBetweenUsers(USER_ID, TARGET_USER_ID);
    }

    @Test
    void sameOwnerDifferentPetIsRejectedByBlockPolicy() {
        InteractionPairContext pair = pair(
                USER_ID,
                USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.SUSPENDED,
                PetStatus.ACTIVE,
                PetStatus.DELETED,
                null,
                CREATED_AT
        );
        stubActiveAndPair(pair);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        verify(userBlockRepository, never()).insertOnConflict(
                USER_ID, USER_ID, SOURCE_PET_ID, TARGET_PET_ID);
    }

    @Test
    void missingTargetPetReportedByLockServiceIsPropagated() {
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(actor());
        when(interactionPairLockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID))
                .thenThrow(new BusinessException(ErrorCode.PET_NOT_FOUND));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND);
        verify(userBlockRepository, never()).insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID);
    }

    @Test
    void changedSourceOwnerIsRejectedAsConcurrentUpdate() {
        InteractionPairContext pair = pair(
                99L,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.ACTIVE,
                PetStatus.ACTIVE,
                PetStatus.ACTIVE,
                null,
                null
        );
        stubActiveAndPair(pair);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    @Test
    void inactiveSourceUserIsRejected() {
        assertSourceStateRejected(pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.SUSPENDED,
                AccountStatus.ACTIVE,
                PetStatus.ACTIVE,
                PetStatus.ACTIVE,
                null,
                null
        ));
    }

    @Test
    void changedActivePetIsRejected() {
        InteractionPairContext pair = validPair();
        pair = new InteractionPairContext(
                new LockedUserContext(USER_ID, AccountStatus.ACTIVE, 999L, "source#0001"),
                pair.targetUser(),
                pair.sourcePet(),
                pair.targetPet()
        );
        assertSourceStateRejected(pair);
    }

    @Test
    void inactiveSourcePetIsRejected() {
        assertSourceStateRejected(pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.ACTIVE,
                PetStatus.SUSPENDED,
                PetStatus.ACTIVE,
                null,
                null
        ));
    }

    @Test
    void deletedSourcePetIsRejected() {
        assertSourceStateRejected(pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.ACTIVE,
                PetStatus.ACTIVE,
                PetStatus.ACTIVE,
                CREATED_AT,
                null
        ));
    }

    @Test
    void inactiveTargetUserDoesNotPreventBlock() {
        InteractionPairContext pair = pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.SUSPENDED,
                PetStatus.ACTIVE,
                PetStatus.ACTIVE,
                null,
                null
        );
        stubSuccessfulBlock(pair, 1);

        assertThat(blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)).created())
                .isTrue();
    }

    @Test
    void inactiveTargetPetDoesNotPreventBlock() {
        InteractionPairContext pair = pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.ACTIVE,
                PetStatus.ACTIVE,
                PetStatus.SUSPENDED,
                null,
                null
        );
        stubSuccessfulBlock(pair, 1);

        assertThat(blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)).created())
                .isTrue();
    }

    @Test
    void softDeletedTargetPetDoesNotPreventBlock() {
        InteractionPairContext pair = pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.WITHDRAWN,
                PetStatus.ACTIVE,
                PetStatus.DELETED,
                null,
                CREATED_AT
        );
        stubSuccessfulBlock(pair, 1);

        assertThat(blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)).created())
                .isTrue();
    }

    @Test
    void invalidListLimitIsRejectedInsteadOfClamped() {
        BusinessException zero = assertThrows(
                BusinessException.class,
                () -> blockService.listBlocks(USER_ID, null, 0));
        BusinessException tooLarge = assertThrows(
                BusinessException.class,
                () -> blockService.listBlocks(USER_ID, null, 101));

        assertThat(zero.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(tooLarge.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private UserBlock stubSuccessfulBlock(InteractionPairContext pair, int inserted) {
        stubActiveAndPair(pair);
        UserBlock block = mock(UserBlock.class);
        when(block.getId()).thenReturn(100L);
        when(block.getBlockedUserId()).thenReturn(TARGET_USER_ID);
        when(block.getCreatedAt()).thenReturn(CREATED_AT);
        when(userBlockRepository.insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID))
                .thenReturn(inserted);
        when(userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                USER_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(block));
        return block;
    }

    private void stubActiveAndPair(InteractionPairContext pair) {
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(actor());
        when(interactionPairLockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID))
                .thenReturn(pair);
    }

    private void assertSourceStateRejected(InteractionPairContext pair) {
        stubActiveAndPair(pair);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);
        verify(userBlockRepository, never()).insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID);
    }

    private InteractionPairContext validPair() {
        return pair(
                USER_ID,
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                AccountStatus.ACTIVE,
                PetStatus.ACTIVE,
                PetStatus.ACTIVE,
                null,
                null
        );
    }

    private InteractionPairContext pair(
            Long sourceOwnerId,
            Long targetOwnerId,
            AccountStatus sourceAccountStatus,
            AccountStatus targetAccountStatus,
            PetStatus sourcePetStatus,
            PetStatus targetPetStatus,
            Instant sourceDeletedAt,
            Instant targetDeletedAt
    ) {
        LockedUserContext sourceUser = new LockedUserContext(
                sourceOwnerId,
                sourceAccountStatus,
                SOURCE_PET_ID,
                "source#0001"
        );
        LockedUserContext targetUser = sourceOwnerId.equals(targetOwnerId)
                ? sourceUser
                : new LockedUserContext(
                        targetOwnerId,
                        targetAccountStatus,
                        null,
                        "target#0002"
                );
        return new InteractionPairContext(
                sourceUser,
                targetUser,
                new LockedPetContext(
                        SOURCE_PET_ID,
                        sourceOwnerId,
                        sourcePetStatus,
                        sourceDeletedAt
                ),
                new LockedPetContext(
                        TARGET_PET_ID,
                        targetOwnerId,
                        targetPetStatus,
                        targetDeletedAt
                )
        );
    }

    private ActivePetContext actor() {
        return new ActivePetContext(
                SOURCE_PET_ID,
                USER_ID,
                "source#0001",
                "source",
                null,
                false
        );
    }
}
