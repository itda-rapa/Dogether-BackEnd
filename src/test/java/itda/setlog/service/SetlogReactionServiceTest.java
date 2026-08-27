package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.MediaStatus;
import itda.notification.service.NotificationCommandService;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.ReactionType;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogReaction;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.AccountStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetlogReactionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ACTIVE_PET_ID = 11L;
    private static final Long AUTHOR_USER_ID = 2L;
    private static final Long AUTHOR_PET_ID = 22L;
    private static final Long SETLOG_ID = 10L;

    @Mock
    private SetlogRepository setlogRepository;
    @Mock
    private SetlogReactionRepository setlogReactionRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock
    private NotificationCommandService notificationCommandService;

    private SetlogReactionService setlogReactionService;

    @BeforeEach
    void setUp() {
        setlogReactionService = new SetlogReactionService(
                setlogRepository,
                setlogReactionRepository,
                petRepository,
                activePetQueryService,
                interactionPairLockService,
                blockRelationshipQueryService,
                notificationCommandService
        );
    }

    @Test
    void addsReactionAndIncrementsCounter() {
        Fixture fixture = stubValidContext();
        given(setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        SETLOG_ID,
                        ACTIVE_PET_ID,
                        ReactionType.CUTE
                )).willReturn(Optional.empty());
        given(fixture.setlog().getCuteCount()).willReturn(1);
        given(fixture.setlog().getLikeCount()).willReturn(0);

        SetlogReactionResponse result =
                setlogReactionService.addReaction(
                        USER_ID,
                        SETLOG_ID,
                        ReactionType.CUTE
                );

        assertThat(result.reacted()).isTrue();
        assertThat(result.cuteCount()).isEqualTo(1);
        then(setlogReactionRepository).should()
                .save(org.mockito.ArgumentMatchers.any(
                        SetlogReaction.class
                ));
        then(fixture.setlog()).should()
                .incrementReaction(ReactionType.CUTE);
    }

    @Test
    void duplicateAddIsIdempotent() {
        Fixture fixture = stubValidContext();
        SetlogReaction existing = mock(SetlogReaction.class);
        given(setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        SETLOG_ID,
                        ACTIVE_PET_ID,
                        ReactionType.LIKE
                )).willReturn(Optional.of(existing));

        SetlogReactionResponse result =
                setlogReactionService.addReaction(
                        USER_ID,
                        SETLOG_ID,
                        ReactionType.LIKE
                );

        assertThat(result.reacted()).isTrue();
        then(setlogReactionRepository).should(never())
                .save(org.mockito.ArgumentMatchers.any());
        then(fixture.setlog()).should(never())
                .incrementReaction(ReactionType.LIKE);
    }

    @Test
    void removesExistingReactionAndDecrementsCounter() {
        Fixture fixture = stubValidContext();
        SetlogReaction existing = mock(SetlogReaction.class);
        given(setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        SETLOG_ID,
                        ACTIVE_PET_ID,
                        ReactionType.CUTE
                )).willReturn(Optional.of(existing));

        SetlogReactionResponse result =
                setlogReactionService.removeReaction(
                        USER_ID,
                        SETLOG_ID,
                        ReactionType.CUTE
                );

        assertThat(result.reacted()).isFalse();
        then(setlogReactionRepository).should().delete(existing);
        then(fixture.setlog()).should()
                .decrementReaction(ReactionType.CUTE);
    }

    @Test
    void missingReactionRemovalIsIdempotent() {
        Fixture fixture = stubValidContext();
        given(setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        SETLOG_ID,
                        ACTIVE_PET_ID,
                        ReactionType.CUTE
                )).willReturn(Optional.empty());

        SetlogReactionResponse result =
                setlogReactionService.removeReaction(
                        USER_ID,
                        SETLOG_ID,
                        ReactionType.CUTE
                );

        assertThat(result.reacted()).isFalse();
        then(setlogReactionRepository).should(never())
                .delete(org.mockito.ArgumentMatchers.any());
        then(fixture.setlog()).should(never())
                .decrementReaction(ReactionType.CUTE);
    }

    @Test
    void sameOwnerReactionIsRejected() {
        ActivePetContext activePet = activePet();
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet);
        given(setlogRepository.findAuthorPetIdById(SETLOG_ID))
                .willReturn(Optional.of(AUTHOR_PET_ID));
        given(interactionPairLockService.lockInteractionPair(
                ACTIVE_PET_ID, AUTHOR_PET_ID
        )).willReturn(pair(USER_ID));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> setlogReactionService.addReaction(
                        USER_ID,
                        SETLOG_ID,
                        ReactionType.CUTE
                )
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SETLOG_SELF_REACTION_FORBIDDEN);
        then(petRepository).shouldHaveNoInteractions();
    }

    @Test
    void blockedAuthorReactionIsRejected() {
        stubValidContext();
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID, AUTHOR_USER_ID
        )).willReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> setlogReactionService.addReaction(
                        USER_ID, SETLOG_ID, ReactionType.LIKE
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BLOCKED_USER);
        then(setlogReactionRepository).shouldHaveNoInteractions();
    }

    @Test
    void nonInteractableSetlogIsReportedAsNotFound() {
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet());
        given(setlogRepository.findAuthorPetIdById(SETLOG_ID))
                .willReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> setlogReactionService.addReaction(
                        USER_ID, SETLOG_ID, ReactionType.CUTE
                )
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SETLOG_NOT_FOUND);
        then(setlogReactionRepository).shouldHaveNoInteractions();
    }

    private Fixture stubValidContext() {
        ActivePetContext activePet = activePet();
        Setlog setlog = mock(Setlog.class);
        Pet authorPet = mock(Pet.class);
        Pet reactorPet = mock(Pet.class);
        lenient().when(activePetQueryService.requireActivePet(USER_ID))
                .thenReturn(activePet);
        lenient().when(setlogRepository.findAuthorPetIdById(SETLOG_ID))
                .thenReturn(Optional.of(AUTHOR_PET_ID));
        lenient().when(interactionPairLockService.lockInteractionPair(
                ACTIVE_PET_ID, AUTHOR_PET_ID
        )).thenReturn(pair(AUTHOR_USER_ID));
        lenient().when(setlogRepository.findInteractableByIdForUpdate(
                SETLOG_ID,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).thenReturn(Optional.of(setlog));
        lenient().when(setlog.getId()).thenReturn(SETLOG_ID);
        lenient().when(setlog.getAuthorPet()).thenReturn(authorPet);
        lenient().when(authorPet.getId()).thenReturn(AUTHOR_PET_ID);
        lenient().when(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                AUTHOR_USER_ID
        )).thenReturn(false);
        lenient().when(petRepository.findById(ACTIVE_PET_ID))
                .thenReturn(Optional.of(reactorPet));
        lenient().when(reactorPet.getId()).thenReturn(ACTIVE_PET_ID);
        return new Fixture(setlog, reactorPet);
    }

    private ActivePetContext activePet() {
        return new ActivePetContext(
                ACTIVE_PET_ID,
                USER_ID,
                "나#A7K2",
                "나",
                null,
                false
        );
    }

    private InteractionPairContext pair(Long targetOwnerId) {
        return new InteractionPairContext(
                new LockedUserContext(
                        USER_ID, AccountStatus.ACTIVE, ACTIVE_PET_ID, "나#A7K2"
                ),
                new LockedUserContext(
                        targetOwnerId, AccountStatus.ACTIVE, AUTHOR_PET_ID,
                        "상대#B7K2"
                ),
                new LockedPetContext(
                        ACTIVE_PET_ID, USER_ID, PetStatus.ACTIVE, null
                ),
                new LockedPetContext(
                        AUTHOR_PET_ID, targetOwnerId, PetStatus.ACTIVE, null
                )
        );
    }

    private record Fixture(
            Setlog setlog,
            Pet reactorPet
    ) {
    }
}
