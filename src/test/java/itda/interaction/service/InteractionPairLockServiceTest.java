package itda.interaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.pet.repository.PetRepository.LockedPetRow;
import itda.pet.repository.PetRepository.PetOwnerRow;
import itda.user.domain.AccountStatus;
import itda.user.repository.UserRepository;
import itda.user.repository.UserRepository.LockedUserRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InteractionPairLockServiceTest {

    private static final long SOURCE_USER_ID = 10L;
    private static final long TARGET_USER_ID = 20L;
    private static final long SOURCE_PET_ID = 100L;
    private static final long TARGET_PET_ID = 200L;
    private static final Instant DELETED_AT = Instant.parse("2026-07-30T10:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private PetRepository petRepository;

    private InteractionPairLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new InteractionPairLockService(userRepository, petRepository);
    }

    @Test
    void rejectsNullSourceBeforeCallingRepositories() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(null, TARGET_PET_ID));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(userRepository, petRepository);
    }

    @Test
    void rejectsNullTargetBeforeCallingRepositories() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(SOURCE_PET_ID, null));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(userRepository, petRepository);
    }

    @Test
    void locksDistinctUsersThenPetsInAscendingOrderAndPreservesDirection() {
        stubTwoPartyRows();

        InteractionPairContext result =
                lockService.lockInteractionPair(TARGET_PET_ID, SOURCE_PET_ID);

        assertThat(result.sourcePet().petId()).isEqualTo(TARGET_PET_ID);
        assertThat(result.sourceUser().userId()).isEqualTo(TARGET_USER_ID);
        assertThat(result.targetPet().petId()).isEqualTo(SOURCE_PET_ID);
        assertThat(result.targetUser().userId()).isEqualTo(SOURCE_USER_ID);

        InOrder order = inOrder(petRepository, userRepository);
        order.verify(petRepository).findOwnerRows(anyCollection());
        order.verify(userRepository).findLockedUserRow(SOURCE_USER_ID);
        order.verify(userRepository).findLockedUserRow(TARGET_USER_ID);
        order.verify(petRepository).findLockedPetRow(SOURCE_PET_ID);
        order.verify(petRepository).findLockedPetRow(TARGET_PET_ID);
    }

    @Test
    void locksOneDistinctUserForDifferentPetsOwnedBySameUser() {
        PetOwnerRow targetOwnerRow = ownerRow(TARGET_PET_ID, SOURCE_USER_ID);
        PetOwnerRow sourceOwnerRow = ownerRow(SOURCE_PET_ID, SOURCE_USER_ID);
        LockedUserRow lockedUserRow = userRow(
                SOURCE_USER_ID,
                AccountStatus.SUSPENDED,
                SOURCE_PET_ID,
                "same#owner"
        );
        LockedPetRow lockedSourcePetRow = petRow(
                SOURCE_PET_ID,
                SOURCE_USER_ID,
                PetStatus.ACTIVE,
                null
        );
        LockedPetRow lockedTargetPetRow = petRow(
                TARGET_PET_ID,
                SOURCE_USER_ID,
                PetStatus.DELETED,
                DELETED_AT
        );
        when(petRepository.findOwnerRows(anyCollection()))
                .thenReturn(List.of(targetOwnerRow, sourceOwnerRow));
        when(userRepository.findLockedUserRow(SOURCE_USER_ID))
                .thenReturn(Optional.of(lockedUserRow));
        when(petRepository.findLockedPetRow(SOURCE_PET_ID))
                .thenReturn(Optional.of(lockedSourcePetRow));
        when(petRepository.findLockedPetRow(TARGET_PET_ID))
                .thenReturn(Optional.of(lockedTargetPetRow));

        InteractionPairContext result =
                lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID);

        assertThat(result.sourceUser()).isEqualTo(result.targetUser());
        assertThat(result.sourceUser().accountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(result.targetPet().status()).isEqualTo(PetStatus.DELETED);
        assertThat(result.targetPet().deletedAt()).isEqualTo(DELETED_AT);
        verify(userRepository).findLockedUserRow(SOURCE_USER_ID);
        verify(userRepository, never()).findLockedUserRow(TARGET_USER_ID);
    }

    @Test
    void locksOneDistinctUserAndPetForSamePetInput() {
        PetOwnerRow ownerRow = ownerRow(SOURCE_PET_ID, SOURCE_USER_ID);
        LockedUserRow lockedUserRow = userRow(
                SOURCE_USER_ID,
                AccountStatus.ACTIVE,
                SOURCE_PET_ID,
                "source#0001"
        );
        LockedPetRow lockedPetRow = petRow(
                SOURCE_PET_ID,
                SOURCE_USER_ID,
                PetStatus.ACTIVE,
                null
        );
        when(petRepository.findOwnerRows(anyCollection()))
                .thenReturn(List.of(ownerRow));
        when(userRepository.findLockedUserRow(SOURCE_USER_ID))
                .thenReturn(Optional.of(lockedUserRow));
        when(petRepository.findLockedPetRow(SOURCE_PET_ID))
                .thenReturn(Optional.of(lockedPetRow));

        InteractionPairContext result =
                lockService.lockInteractionPair(SOURCE_PET_ID, SOURCE_PET_ID);

        assertThat(result.sourcePet()).isEqualTo(result.targetPet());
        assertThat(result.sourceUser()).isEqualTo(result.targetUser());
        verify(petRepository).findOwnerRows(anyCollection());
        verify(userRepository).findLockedUserRow(SOURCE_USER_ID);
        verify(petRepository).findLockedPetRow(SOURCE_PET_ID);
        verify(petRepository, never()).findLockedPetRow(TARGET_PET_ID);
    }

    @Test
    void missingOwnerProjectionIsPetNotFoundAndDoesNotAcquireLocks() {
        PetOwnerRow sourceOwnerRow = ownerRow(SOURCE_PET_ID, SOURCE_USER_ID);
        when(petRepository.findOwnerRows(anyCollection()))
                .thenReturn(List.of(sourceOwnerRow));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND);
        verifyNoInteractions(userRepository);
        verify(petRepository, never()).findLockedPetRow(SOURCE_PET_ID);
        verify(petRepository, never()).findLockedPetRow(TARGET_PET_ID);
    }

    @Test
    void missingLockedUserIsConcurrentUpdateConflict() {
        stubOwnerRows();
        when(userRepository.findLockedUserRow(SOURCE_USER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        verify(petRepository, never()).findLockedPetRow(SOURCE_PET_ID);
    }

    @Test
    void missingLockedPetIsConcurrentUpdateConflict() {
        stubOwnerRows();
        stubUserRows();
        when(petRepository.findLockedPetRow(SOURCE_PET_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    @Test
    void changedOwnerAfterProjectionIsConcurrentUpdateConflict() {
        stubOwnerRows();
        stubUserRows();
        LockedPetRow changedOwnerRow = mock(LockedPetRow.class);
        when(changedOwnerRow.getPetId()).thenReturn(SOURCE_PET_ID);
        when(changedOwnerRow.getOwnerUserId()).thenReturn(TARGET_USER_ID);
        when(petRepository.findLockedPetRow(SOURCE_PET_ID))
                .thenReturn(Optional.of(changedOwnerRow));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID));

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    @Test
    void snapshotsRawStatesWithoutApplyingInteractionPolicy() {
        PetOwnerRow targetOwnerRow = ownerRow(TARGET_PET_ID, TARGET_USER_ID);
        PetOwnerRow sourceOwnerRow = ownerRow(SOURCE_PET_ID, SOURCE_USER_ID);
        LockedUserRow lockedSourceUserRow = userRow(
                SOURCE_USER_ID,
                AccountStatus.WITHDRAWN,
                999L,
                "source#0001"
        );
        LockedUserRow lockedTargetUserRow = userRow(
                TARGET_USER_ID,
                AccountStatus.SUSPENDED,
                null,
                "target#0002"
        );
        LockedPetRow lockedSourcePetRow = petRow(
                SOURCE_PET_ID,
                SOURCE_USER_ID,
                PetStatus.SUSPENDED,
                DELETED_AT
        );
        LockedPetRow lockedTargetPetRow = petRow(
                TARGET_PET_ID,
                TARGET_USER_ID,
                PetStatus.DELETED,
                DELETED_AT
        );
        when(petRepository.findOwnerRows(anyCollection()))
                .thenReturn(List.of(targetOwnerRow, sourceOwnerRow));
        when(userRepository.findLockedUserRow(SOURCE_USER_ID))
                .thenReturn(Optional.of(lockedSourceUserRow));
        when(userRepository.findLockedUserRow(TARGET_USER_ID))
                .thenReturn(Optional.of(lockedTargetUserRow));
        when(petRepository.findLockedPetRow(SOURCE_PET_ID))
                .thenReturn(Optional.of(lockedSourcePetRow));
        when(petRepository.findLockedPetRow(TARGET_PET_ID))
                .thenReturn(Optional.of(lockedTargetPetRow));

        InteractionPairContext result =
                lockService.lockInteractionPair(SOURCE_PET_ID, TARGET_PET_ID);

        assertThat(result.sourceUser().accountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
        assertThat(result.sourceUser().activePetId()).isEqualTo(999L);
        assertThat(result.sourcePet().status()).isEqualTo(PetStatus.SUSPENDED);
        assertThat(result.sourcePet().deletedAt()).isEqualTo(DELETED_AT);
        assertThat(result.targetUser().accountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(result.targetPet().status()).isEqualTo(PetStatus.DELETED);
    }

    private void stubTwoPartyRows() {
        stubOwnerRows();
        stubUserRows();
        LockedPetRow sourcePetRow = petRow(
                SOURCE_PET_ID,
                SOURCE_USER_ID,
                PetStatus.ACTIVE,
                null
        );
        LockedPetRow targetPetRow = petRow(
                TARGET_PET_ID,
                TARGET_USER_ID,
                PetStatus.SUSPENDED,
                DELETED_AT
        );
        when(petRepository.findLockedPetRow(SOURCE_PET_ID))
                .thenReturn(Optional.of(sourcePetRow));
        when(petRepository.findLockedPetRow(TARGET_PET_ID))
                .thenReturn(Optional.of(targetPetRow));
    }

    private void stubOwnerRows() {
        PetOwnerRow targetOwnerRow = ownerRow(TARGET_PET_ID, TARGET_USER_ID);
        PetOwnerRow sourceOwnerRow = ownerRow(SOURCE_PET_ID, SOURCE_USER_ID);
        when(petRepository.findOwnerRows(anyCollection()))
                .thenReturn(List.of(targetOwnerRow, sourceOwnerRow));
    }

    private void stubUserRows() {
        LockedUserRow sourceUserRow = userRow(
                SOURCE_USER_ID,
                AccountStatus.ACTIVE,
                SOURCE_PET_ID,
                "source#0001"
        );
        LockedUserRow targetUserRow = userRow(
                TARGET_USER_ID,
                AccountStatus.SUSPENDED,
                null,
                "target#0002"
        );
        when(userRepository.findLockedUserRow(SOURCE_USER_ID))
                .thenReturn(Optional.of(sourceUserRow));
        when(userRepository.findLockedUserRow(TARGET_USER_ID))
                .thenReturn(Optional.of(targetUserRow));
    }

    private PetOwnerRow ownerRow(Long petId, Long ownerUserId) {
        PetOwnerRow row = mock(PetOwnerRow.class);
        when(row.getPetId()).thenReturn(petId);
        when(row.getOwnerUserId()).thenReturn(ownerUserId);
        return row;
    }

    private LockedUserRow userRow(
            Long userId,
            AccountStatus accountStatus,
            Long activePetId,
            String publicTag
    ) {
        LockedUserRow row = mock(LockedUserRow.class);
        when(row.getUserId()).thenReturn(userId);
        when(row.getAccountStatus()).thenReturn(accountStatus.name());
        when(row.getActivePetId()).thenReturn(activePetId);
        when(row.getPublicTag()).thenReturn(publicTag);
        return row;
    }

    private LockedPetRow petRow(
            Long petId,
            Long ownerUserId,
            PetStatus status,
            Instant deletedAt
    ) {
        LockedPetRow row = mock(LockedPetRow.class);
        when(row.getPetId()).thenReturn(petId);
        when(row.getOwnerUserId()).thenReturn(ownerUserId);
        when(row.getStatus()).thenReturn(status.name());
        when(row.getDeletedAt()).thenReturn(deletedAt);
        return row;
    }
}
