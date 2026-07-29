package itda.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.block.domain.UserBlock;
import itda.block.dto.BlockCreateRequest;
import itda.block.repository.UserBlockRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.service.FriendBlockCleanupService;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    private static final long USER_ID = 1L;
    private static final long SOURCE_PET_ID = 11L;
    private static final long TARGET_USER_ID = 2L;
    private static final long TARGET_PET_ID = 22L;

    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private FriendBlockCleanupService friendBlockCleanupService;

    @InjectMocks
    private BlockService blockService;

    @Test
    void createsBlockWithPetContextAndCleansEveryFriendRelation() {
        Fixture fixture = stubValidFixture();
        when(userBlockRepository.insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID))
                .thenReturn(1);
        when(userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                USER_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(fixture.block()));

        BlockService.BlockResult result =
                blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID));

        assertThat(result.created()).isTrue();
        assertThat(result.block().blockId()).isEqualTo(100L);
        assertThat(result.block().blockedUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(result.block().blockedUserPublicTag()).isEqualTo("target#0002");
        verify(friendBlockCleanupService).cleanupBetweenUsers(USER_ID, TARGET_USER_ID);
    }

    @Test
    void existingBlockReturnsCreatedFalseAndRepairsRelationsIdempotently() {
        Fixture fixture = stubValidFixture();
        when(userBlockRepository.insertOnConflict(
                USER_ID, TARGET_USER_ID, SOURCE_PET_ID, TARGET_PET_ID))
                .thenReturn(0);
        when(userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                USER_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(fixture.block()));

        BlockService.BlockResult result =
                blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID));

        assertThat(result.created()).isFalse();
        verify(friendBlockCleanupService).cleanupBetweenUsers(USER_ID, TARGET_USER_ID);
    }

    @Test
    void sameOwnerIsRejected() {
        ActivePetContext actor = actor();
        User owner = mock(User.class);
        Pet target = mock(Pet.class);
        when(owner.getId()).thenReturn(USER_ID);
        when(target.getOwner()).thenReturn(owner);
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(actor);
        when(petRepository.findById(TARGET_PET_ID)).thenReturn(Optional.of(target));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
    }

    @Test
    void missingTargetPetIsRejected() {
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(actor());
        when(petRepository.findById(TARGET_PET_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> blockService.block(USER_ID, new BlockCreateRequest(TARGET_PET_ID)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND);
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

    private Fixture stubValidFixture() {
        ActivePetContext actor = actor();
        User targetOwner = mock(User.class);
        Pet targetPet = mock(Pet.class);
        UserBlock block = mock(UserBlock.class);
        Instant createdAt = Instant.parse("2026-07-29T10:00:00Z");

        when(targetOwner.getId()).thenReturn(TARGET_USER_ID);
        when(targetOwner.getPublicTag()).thenReturn("target#0002");
        when(targetPet.getId()).thenReturn(TARGET_PET_ID);
        when(targetPet.getOwner()).thenReturn(targetOwner);
        when(block.getId()).thenReturn(100L);
        when(block.getBlockedUserId()).thenReturn(TARGET_USER_ID);
        when(block.getCreatedAt()).thenReturn(createdAt);
        when(activePetQueryService.requireActivePet(USER_ID)).thenReturn(actor);
        when(petRepository.findById(TARGET_PET_ID)).thenReturn(Optional.of(targetPet));

        return new Fixture(targetPet, targetOwner, block);
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

    private record Fixture(Pet targetPet, User targetOwner, UserBlock block) {}
}
