package itda.friend.service.query;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.dto.response.FriendPetListItemResponse;
import itda.friend.dto.response.PetFriendListResponse;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipListRow;
import itda.friend.support.FriendshipCursorCodec;
import itda.friend.support.FriendshipCursorCodec.CursorPayload;
import itda.pet.service.MyPetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendshipQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final FriendshipRepository friendshipRepository;
    private final MyPetQueryService myPetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;

    public FriendshipQueryService(
            FriendshipRepository friendshipRepository,
            MyPetQueryService myPetQueryService,
            PetDisplayQueryService petDisplayQueryService
    ) {
        this.friendshipRepository = friendshipRepository;
        this.myPetQueryService = myPetQueryService;
        this.petDisplayQueryService = petDisplayQueryService;
    }

    @Transactional(readOnly = true)
    public PetFriendListResponse listFriends(
            Long userId,
            Long petId,
            String cursor,
            Integer rawLimit
    ) {
        myPetQueryService.getMyPet(userId, petId);
        int limit = validateLimit(rawLimit);
        CursorPayload payload = FriendshipCursorCodec.decode(cursor);

        List<FriendshipListRow> rows =
                friendshipRepository.findFriendPage(
                        petId,
                        payload == null ? null : payload.createdAt(),
                        payload == null ? null : payload.friendshipId(),
                        limit + 1
                );
        boolean hasNext = rows.size() > limit;
        List<FriendshipListRow> page = hasNext
                ? rows.subList(0, limit)
                : rows;

        Set<Long> counterpartPetIds = new LinkedHashSet<>();
        for (FriendshipListRow row : page) {
            counterpartPetIds.add(row.getCounterpartPetId());
        }
        Map<Long, PetDisplaySummary> pets = counterpartPetIds.isEmpty()
                ? Map.of()
                : petDisplayQueryService.getPetDisplaySummaries(
                        counterpartPetIds
                );
        List<FriendPetListItemResponse> items = page.stream()
                .map(row -> FriendPetListItemResponse.from(
                        pets.get(row.getCounterpartPetId()),
                        FriendRelationship.FRIEND
                ))
                .toList();

        String nextCursor = hasNext && !page.isEmpty()
                ? FriendshipCursorCodec.encode(
                        page.get(page.size() - 1).getFriendshipId(),
                        page.get(page.size() - 1).getCreatedAt()
                )
                : null;
        return new PetFriendListResponse(
                items,
                CursorPage.of(nextCursor, hasNext)
        );
    }

    private int validateLimit(Integer rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (rawLimit < 1 || rawLimit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return rawLimit;
    }
}
