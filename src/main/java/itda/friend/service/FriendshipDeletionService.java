package itda.friend.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.repository.FriendshipRepository;
import itda.pet.service.MyPetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendshipDeletionService {

    private final MyPetQueryService myPetQueryService;
    private final FriendshipRepository friendshipRepository;

    @Transactional
    public void deleteFriendship(
            Long authenticatedUserId,
            Long sourcePetId,
            Long friendPetId
    ) {
        myPetQueryService.requireOwnedUndeletedPet(
                authenticatedUserId,
                sourcePetId
        );

        long petLowId = Math.min(sourcePetId, friendPetId);
        long petHighId = Math.max(sourcePetId, friendPetId);
        int deletedCount = friendshipRepository.deletePair(
                petLowId,
                petHighId
        );
        if (deletedCount == 0) {
            throw new BusinessException(ErrorCode.FRIENDSHIP_NOT_FOUND);
        }
    }
}
