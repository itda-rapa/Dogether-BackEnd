package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetSearchItemResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PetSearchQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SOURCE_PET_ID = 10L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long TARGET_PET_ID = 20L;
    private static final String PUBLIC_TAG = "몽이#A7K2";

    @Mock
    private PetDisplayQueryService petDisplayQueryService;

    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;

    @Mock
    private ActivePetQueryService activePetQueryService;

    @Mock
    private FriendRelationshipQueryService friendRelationshipQueryService;

    private PetSearchQueryService service;

    @BeforeEach
    void setUp() {
        service = new PetSearchQueryService(
                petDisplayQueryService,
                blockRelationshipQueryService,
                activePetQueryService,
                friendRelationshipQueryService
        );
    }

    @Test
    void rejectsNullRequiredValuesWithoutQueries() {
        assertValidationFailed(() -> service.search(null, PUBLIC_TAG));
        assertValidationFailed(() -> service.search(USER_ID, null));

        then(petDisplayQueryService).shouldHaveNoInteractions();
        then(blockRelationshipQueryService).shouldHaveNoInteractions();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void returnsEmptyWithoutDownstreamQueriesWhenPetIsNotSearchable() {
        given(petDisplayQueryService.findSearchablePetDisplaySummary(PUBLIC_TAG))
                .willReturn(Optional.empty());

        Optional<PetSearchItemResponse> result = service.search(
                USER_ID,
                PUBLIC_TAG
        );

        assertThat(result).isEmpty();
        then(blockRelationshipQueryService).shouldHaveNoInteractions();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void hidesSameOwnerWithoutBlockOrRelationshipQueries() {
        PetDisplaySummary ownPet = summary(USER_ID);
        given(petDisplayQueryService.findSearchablePetDisplaySummary(PUBLIC_TAG))
                .willReturn(Optional.of(ownPet));

        Optional<PetSearchItemResponse> result = service.search(
                USER_ID,
                PUBLIC_TAG
        );

        assertThat(result).isEmpty();
        then(blockRelationshipQueryService).shouldHaveNoInteractions();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void hidesBlockedOwnerWithoutActivePetOrFriendQueries() {
        givenTarget();
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                TARGET_USER_ID
        )).willReturn(true);

        Optional<PetSearchItemResponse> result = service.search(
                USER_ID,
                PUBLIC_TAG
        );

        assertThat(result).isEmpty();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void returnsNullRelationshipForL1WithoutFriendQueries() {
        givenVisibleTarget();
        given(activePetQueryService.findActivePet(USER_ID))
                .willReturn(Optional.empty());

        PetSearchItemResponse result = service.search(USER_ID, PUBLIC_TAG)
                .orElseThrow();

        assertThat(result.petId()).isEqualTo(TARGET_PET_ID);
        assertThat(result.publicTag()).isEqualTo(PUBLIC_TAG);
        assertThat(result.nickname()).isEqualTo("몽이");
        assertThat(result.profileUrl()).isNull();
        assertThat(result.verified()).isFalse();
        assertThat(result.relationship()).isNull();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(FriendRelationship.class)
    void returnsRelationshipForL2(FriendRelationship relationship) {
        givenVisibleTarget();
        given(activePetQueryService.findActivePet(USER_ID))
                .willReturn(Optional.of(activePet()));
        given(friendRelationshipQueryService.getRelationships(
                SOURCE_PET_ID,
                List.of(TARGET_PET_ID)
        )).willReturn(Map.of(TARGET_PET_ID, relationship));

        PetSearchItemResponse result = service.search(USER_ID, PUBLIC_TAG)
                .orElseThrow();

        assertThat(result.relationship()).isEqualTo(relationship);
    }

    @Test
    void defaultsToNoneWhenRelationshipMapHasNoTarget() {
        givenVisibleTarget();
        given(activePetQueryService.findActivePet(USER_ID))
                .willReturn(Optional.of(activePet()));
        given(friendRelationshipQueryService.getRelationships(
                SOURCE_PET_ID,
                List.of(TARGET_PET_ID)
        )).willReturn(Map.of());

        PetSearchItemResponse result = service.search(USER_ID, PUBLIC_TAG)
                .orElseThrow();

        assertThat(result.relationship()).isEqualTo(FriendRelationship.NONE);
    }

    @Test
    void evaluatesVisibilityBeforeActivePetAndRelationship() {
        givenVisibleTarget();
        given(activePetQueryService.findActivePet(USER_ID))
                .willReturn(Optional.empty());

        service.search(USER_ID, PUBLIC_TAG);

        InOrder order = Mockito.inOrder(
                petDisplayQueryService,
                blockRelationshipQueryService,
                activePetQueryService
        );
        order.verify(petDisplayQueryService)
                .findSearchablePetDisplaySummary(PUBLIC_TAG);
        order.verify(blockRelationshipQueryService)
                .existsBlockBetween(USER_ID, TARGET_USER_ID);
        order.verify(activePetQueryService).findActivePet(USER_ID);
    }

    private void givenTarget() {
        given(petDisplayQueryService.findSearchablePetDisplaySummary(PUBLIC_TAG))
                .willReturn(Optional.of(summary(TARGET_USER_ID)));
    }

    private void givenVisibleTarget() {
        givenTarget();
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                TARGET_USER_ID
        )).willReturn(false);
    }

    private PetDisplaySummary summary(Long ownerUserId) {
        return new PetDisplaySummary(
                TARGET_PET_ID,
                ownerUserId,
                PUBLIC_TAG,
                "몽이",
                null,
                false,
                PetStatus.ACTIVE,
                null
        );
    }

    private ActivePetContext activePet() {
        return new ActivePetContext(
                SOURCE_PET_ID,
                USER_ID,
                "보리#B8M3",
                "보리",
                null,
                false
        );
    }

    private void assertValidationFailed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
