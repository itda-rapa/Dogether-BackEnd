package itda.friend.service.query;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestListResponse;
import itda.friend.dto.response.FriendRequestPetResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.FriendRequestListRow;
import itda.friend.support.FriendRequestCursorCodec;
import itda.friend.support.FriendRequestCursorCodec.CursorPayload;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRequestQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final FriendRequestRepository friendRequestRepository;
    private final ActivePetQueryService activePetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final Clock clock;

    @Autowired
    public FriendRequestQueryService(
            FriendRequestRepository friendRequestRepository,
            ActivePetQueryService activePetQueryService,
            PetDisplayQueryService petDisplayQueryService
    ) {
        this(
                friendRequestRepository,
                activePetQueryService,
                petDisplayQueryService,
                Clock.systemUTC()
        );
    }

    FriendRequestQueryService(
            FriendRequestRepository friendRequestRepository,
            ActivePetQueryService activePetQueryService,
            PetDisplayQueryService petDisplayQueryService,
            Clock clock
    ) {
        this.friendRequestRepository = friendRequestRepository;
        this.activePetQueryService = activePetQueryService;
        this.petDisplayQueryService = petDisplayQueryService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FriendRequestListResponse listReceived(
            Long userId,
            String cursor,
            Integer rawLimit
    ) {
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(userId);
        int limit = validateLimit(rawLimit);
        CursorPayload payload = FriendRequestCursorCodec.decode(cursor);
        Instant now = clock.instant();

        List<FriendRequestListRow> rows =
                friendRequestRepository.findReceivedPendingPage(
                        activePet.petId(),
                        now,
                        payload == null ? null : payload.requestedAt(),
                        payload == null ? null : payload.requestId(),
                        limit + 1
                );
        return toResponse(rows, limit, FriendRequestDirection.RECEIVED);
    }

    @Transactional(readOnly = true)
    public FriendRequestListResponse listSent(
            Long userId,
            String cursor,
            Integer rawLimit
    ) {
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(userId);
        int limit = validateLimit(rawLimit);
        CursorPayload payload = FriendRequestCursorCodec.decode(cursor);
        Instant now = clock.instant();

        List<FriendRequestListRow> rows =
                friendRequestRepository.findSentPendingPage(
                        activePet.petId(),
                        now,
                        payload == null ? null : payload.requestedAt(),
                        payload == null ? null : payload.requestId(),
                        limit + 1
                );
        return toResponse(rows, limit, FriendRequestDirection.SENT);
    }

    private FriendRequestListResponse toResponse(
            List<FriendRequestListRow> rows,
            int limit,
            FriendRequestDirection direction
    ) {
        boolean hasNext = rows.size() > limit;
        List<FriendRequestListRow> page = hasNext
                ? rows.subList(0, limit)
                : rows;

        Set<Long> petIds = new LinkedHashSet<>();
        for (FriendRequestListRow row : page) {
            petIds.add(row.getRequesterPetId());
            petIds.add(row.getTargetPetId());
        }
        Map<Long, PetDisplaySummary> pets = petIds.isEmpty()
                ? Map.of()
                : petDisplayQueryService.getPetDisplaySummaries(petIds);

        List<FriendRequestResponse> items = page.stream()
                .map(row -> toResponse(row, direction, pets))
                .toList();
        String nextCursor = hasNext && !page.isEmpty()
                ? FriendRequestCursorCodec.encode(
                        page.get(page.size() - 1).getRequestId(),
                        page.get(page.size() - 1).getRequestedAt()
                )
                : null;
        return new FriendRequestListResponse(
                items,
                CursorPage.of(nextCursor, hasNext)
        );
    }

    private FriendRequestResponse toResponse(
            FriendRequestListRow row,
            FriendRequestDirection direction,
            Map<Long, PetDisplaySummary> pets
    ) {
        FriendRelationship requesterRelationship =
                direction == FriendRequestDirection.RECEIVED
                        ? FriendRelationship.REQUEST_RECEIVED
                        : FriendRelationship.NONE;
        FriendRelationship targetRelationship =
                direction == FriendRequestDirection.SENT
                        ? FriendRelationship.REQUEST_SENT
                        : FriendRelationship.NONE;

        return new FriendRequestResponse(
                row.getRequestId(),
                FriendRequestPetResponse.from(
                        pets.get(row.getRequesterPetId()),
                        requesterRelationship
                ),
                FriendRequestPetResponse.from(
                        pets.get(row.getTargetPetId()),
                        targetRelationship
                ),
                FriendRequestStatus.valueOf(row.getStatus()),
                row.getRequestedAt(),
                row.getRespondedAt(),
                row.getExpiresAt(),
                null
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

    private enum FriendRequestDirection {
        RECEIVED,
        SENT
    }
}
