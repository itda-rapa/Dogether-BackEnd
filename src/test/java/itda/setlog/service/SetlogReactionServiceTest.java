package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.MediaStatus;
import itda.pet.domain.Pet;
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
import itda.user.domain.User;
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
    private BlockRelationshipQueryService blockRelationshipQueryService;

    private SetlogReactionService setlogReactionService;

    @BeforeEach
    void setUp() {
        setlogReactionService = new SetlogReactionService(
                setlogRepository,
                setlogReactionRepository,
                petRepository,
                activePetQueryService,
                blockRelationshipQueryService
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
        Setlog setlog = mock(Setlog.class);
        Pet authorPet = mock(Pet.class);
        User owner = mock(User.class);
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet);
        given(setlogRepository.findVisibleSeedByIdForUpdate(
                SETLOG_ID,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).willReturn(Optional.of(setlog));
        given(setlog.getAuthorPet()).willReturn(authorPet);
        given(authorPet.getOwner()).willReturn(owner);
        given(owner.getId()).willReturn(USER_ID);

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

    private Fixture stubValidContext() {
        ActivePetContext activePet = activePet();
        Setlog setlog = mock(Setlog.class);
        Pet authorPet = mock(Pet.class);
        User authorOwner = mock(User.class);
        Pet reactorPet = mock(Pet.class);
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet);
        given(setlogRepository.findVisibleSeedByIdForUpdate(
                SETLOG_ID,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).willReturn(Optional.of(setlog));
        given(setlog.getId()).willReturn(SETLOG_ID);
        given(setlog.getAuthorPet()).willReturn(authorPet);
        given(authorPet.getOwner()).willReturn(authorOwner);
        given(authorOwner.getId()).willReturn(AUTHOR_USER_ID);
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                AUTHOR_USER_ID
        )).willReturn(false);
        given(petRepository.findById(ACTIVE_PET_ID))
                .willReturn(Optional.of(reactorPet));
        given(reactorPet.getId()).willReturn(ACTIVE_PET_ID);
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

    private record Fixture(
            Setlog setlog,
            Pet reactorPet
    ) {
    }
}
