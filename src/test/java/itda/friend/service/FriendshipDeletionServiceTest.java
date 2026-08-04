package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.repository.FriendshipRepository;
import itda.pet.service.MyPetQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendshipDeletionServiceTest {

    @Mock
    private MyPetQueryService myPetQueryService;

    @Mock
    private FriendshipRepository friendshipRepository;

    private FriendshipDeletionService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipDeletionService(
                myPetQueryService,
                friendshipRepository
        );
    }

    @Test
    void deletesCanonicalPairAfterSourceGuard() {
        given(friendshipRepository.deletePair(10L, 20L)).willReturn(1);

        service.deleteFriendship(1L, 10L, 20L);

        InOrder order = inOrder(myPetQueryService, friendshipRepository);
        order.verify(myPetQueryService).requireOwnedUndeletedPet(1L, 10L);
        order.verify(friendshipRepository).deletePair(10L, 20L);
    }

    @Test
    void canonicalizesReverseInputOrder() {
        given(friendshipRepository.deletePair(10L, 20L)).willReturn(1);

        service.deleteFriendship(1L, 20L, 10L);

        then(myPetQueryService).should()
                .requireOwnedUndeletedPet(1L, 20L);
        then(friendshipRepository).should().deletePair(10L, 20L);
    }

    @Test
    void returnsFriendshipNotFoundWhenNoRowWasDeleted() {
        given(friendshipRepository.deletePair(10L, 20L)).willReturn(0);

        assertThatThrownBy(() ->
                service.deleteFriendship(1L, 10L, 20L)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.FRIENDSHIP_NOT_FOUND);
    }

    @Test
    void samePetIdUsesDeleteResultContract() {
        given(friendshipRepository.deletePair(10L, 10L)).willReturn(0);

        assertThatThrownBy(() ->
                service.deleteFriendship(1L, 10L, 10L)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.FRIENDSHIP_NOT_FOUND);
    }

    @Test
    void doesNotDeleteWhenSourceGuardFails() {
        BusinessException failure =
                new BusinessException(ErrorCode.PET_NOT_OWNED);
        willThrow(failure)
                .given(myPetQueryService)
                .requireOwnedUndeletedPet(1L, 10L);

        assertThatThrownBy(() ->
                service.deleteFriendship(1L, 10L, 20L)
        ).isSameAs(failure);

        then(friendshipRepository).should(never())
                .deletePair(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }
}
