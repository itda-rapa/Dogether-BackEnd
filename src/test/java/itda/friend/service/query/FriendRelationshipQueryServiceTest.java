package itda.friend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.PendingFriendRequestRelationshipRow;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipRelationshipRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendRelationshipQueryServiceTest {

    private static final Long SOURCE_PET_ID = 10L;
    private static final Long FIRST_TARGET_PET_ID = 20L;
    private static final Long SECOND_TARGET_PET_ID = 30L;
    private static final Long THIRD_TARGET_PET_ID = 40L;
    private static final Instant NOW =
            Instant.parse("2026-07-29T07:00:00Z");

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private FriendRequestRepository friendRequestRepository;

    @Mock
    private Clock clock;

    private FriendRelationshipQueryService service;

    @BeforeEach
    void setUp() {
        service = new FriendRelationshipQueryService(
                friendshipRepository,
                friendRequestRepository,
                clock
        );
    }

    @Test
    void rejectsNullSourceWithoutClockOrRepositoryAccess() {
        assertValidationFailed(() ->
                service.getRelationships(null, List.of(FIRST_TARGET_PET_ID))
        );

        then(clock).shouldHaveNoInteractions();
        then(friendshipRepository).shouldHaveNoInteractions();
        then(friendRequestRepository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsNullTargetsWithoutClockOrRepositoryAccess() {
        assertValidationFailed(() ->
                service.getRelationships(SOURCE_PET_ID, null)
        );

        then(clock).shouldHaveNoInteractions();
        then(friendshipRepository).shouldHaveNoInteractions();
        then(friendRequestRepository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsNullTargetElementWithoutClockOrRepositoryAccess() {
        List<Long> targetPetIds = new ArrayList<>();
        targetPetIds.add(FIRST_TARGET_PET_ID);
        targetPetIds.add(null);

        assertValidationFailed(() ->
                service.getRelationships(SOURCE_PET_ID, targetPetIds)
        );

        then(clock).shouldHaveNoInteractions();
        then(friendshipRepository).shouldHaveNoInteractions();
        then(friendRequestRepository).shouldHaveNoInteractions();
    }

    @Test
    void returnsEmptyMapForEmptyTargetsWithoutClockOrRepositoryAccess() {
        Map<Long, FriendRelationship> result =
                service.getRelationships(SOURCE_PET_ID, List.of());

        assertThat(result).isEmpty();
        then(clock).shouldHaveNoInteractions();
        then(friendshipRepository).shouldHaveNoInteractions();
        then(friendRequestRepository).shouldHaveNoInteractions();
    }

    @Test
    void returnsNoneForSourceOnlyWithoutClockOrRepositoryAccess() {
        Map<Long, FriendRelationship> result = service.getRelationships(
                SOURCE_PET_ID,
                List.of(SOURCE_PET_ID, SOURCE_PET_ID)
        );

        assertThat(result)
                .hasSize(1)
                .containsEntry(SOURCE_PET_ID, FriendRelationship.NONE);
        then(clock).shouldHaveNoInteractions();
        then(friendshipRepository).shouldHaveNoInteractions();
        then(friendRequestRepository).shouldHaveNoInteractions();
    }

    @Test
    void removesDuplicatesAndQueriesOnlyNonSelfTargetsOnce() {
        given(clock.instant()).willReturn(NOW);

        Map<Long, FriendRelationship> result = service.getRelationships(
                SOURCE_PET_ID,
                List.of(
                        SOURCE_PET_ID,
                        FIRST_TARGET_PET_ID,
                        FIRST_TARGET_PET_ID,
                        SECOND_TARGET_PET_ID,
                        SOURCE_PET_ID
                )
        );

        assertThat(result)
                .hasSize(3)
                .containsOnlyKeys(
                        SOURCE_PET_ID,
                        FIRST_TARGET_PET_ID,
                        SECOND_TARGET_PET_ID
                )
                .containsEntry(SOURCE_PET_ID, FriendRelationship.NONE)
                .containsEntry(FIRST_TARGET_PET_ID, FriendRelationship.NONE)
                .containsEntry(SECOND_TARGET_PET_ID, FriendRelationship.NONE);
        then(friendRequestRepository).should(times(1))
                .findActivePendingRelationships(
                        eq(SOURCE_PET_ID),
                        targetIds(FIRST_TARGET_PET_ID, SECOND_TARGET_PET_ID),
                        eq(NOW)
                );
        then(friendshipRepository).should(times(1))
                .findRelationships(
                        eq(SOURCE_PET_ID),
                        targetIds(FIRST_TARGET_PET_ID, SECOND_TARGET_PET_ID)
                );
    }

    @Test
    void resolvesSentReceivedAndFriendRelationships() {
        given(clock.instant()).willReturn(NOW);
        PendingFriendRequestRelationshipRow sentRequest =
                pendingRow(SOURCE_PET_ID, FIRST_TARGET_PET_ID);
        PendingFriendRequestRelationshipRow receivedRequest =
                pendingRow(SECOND_TARGET_PET_ID, SOURCE_PET_ID);
        FriendshipRelationshipRow friendship =
                friendshipRow(SOURCE_PET_ID, THIRD_TARGET_PET_ID);
        given(friendRequestRepository.findActivePendingRelationships(
                eq(SOURCE_PET_ID),
                targetIds(
                        FIRST_TARGET_PET_ID,
                        SECOND_TARGET_PET_ID,
                        THIRD_TARGET_PET_ID
                ),
                eq(NOW)
        )).willReturn(List.of(
                sentRequest,
                receivedRequest
        ));
        given(friendshipRepository.findRelationships(
                eq(SOURCE_PET_ID),
                targetIds(
                        FIRST_TARGET_PET_ID,
                        SECOND_TARGET_PET_ID,
                        THIRD_TARGET_PET_ID
                )
        )).willReturn(List.of(
                friendship
        ));

        Map<Long, FriendRelationship> result = service.getRelationships(
                SOURCE_PET_ID,
                List.of(
                        FIRST_TARGET_PET_ID,
                        SECOND_TARGET_PET_ID,
                        THIRD_TARGET_PET_ID
                )
        );

        assertThat(result)
                .containsEntry(
                        FIRST_TARGET_PET_ID,
                        FriendRelationship.REQUEST_SENT
                )
                .containsEntry(
                        SECOND_TARGET_PET_ID,
                        FriendRelationship.REQUEST_RECEIVED
                )
                .containsEntry(
                        THIRD_TARGET_PET_ID,
                        FriendRelationship.FRIEND
                );
    }

    @Test
    void friendshipOverridesPendingRelationship() {
        given(clock.instant()).willReturn(NOW);
        PendingFriendRequestRelationshipRow pendingRequest =
                pendingRow(SOURCE_PET_ID, FIRST_TARGET_PET_ID);
        FriendshipRelationshipRow friendship =
                friendshipRow(SOURCE_PET_ID, FIRST_TARGET_PET_ID);
        given(friendRequestRepository.findActivePendingRelationships(
                eq(SOURCE_PET_ID),
                targetIds(FIRST_TARGET_PET_ID),
                eq(NOW)
        )).willReturn(List.of(
                pendingRequest
        ));
        given(friendshipRepository.findRelationships(
                eq(SOURCE_PET_ID),
                targetIds(FIRST_TARGET_PET_ID)
        )).willReturn(List.of(
                friendship
        ));

        Map<Long, FriendRelationship> result = service.getRelationships(
                SOURCE_PET_ID,
                List.of(FIRST_TARGET_PET_ID)
        );

        assertThat(result).containsEntry(
                FIRST_TARGET_PET_ID,
                FriendRelationship.FRIEND
        );
    }

    @Test
    void returnsUnmodifiableMap() {
        given(clock.instant()).willReturn(NOW);

        Map<Long, FriendRelationship> result = service.getRelationships(
                SOURCE_PET_ID,
                List.of(FIRST_TARGET_PET_ID)
        );

        assertThatThrownBy(() ->
                result.put(SECOND_TARGET_PET_ID, FriendRelationship.NONE)
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readsClockAndEachRepositoryExactlyOnce() {
        given(clock.instant()).willReturn(NOW);

        service.getRelationships(
                SOURCE_PET_ID,
                List.of(FIRST_TARGET_PET_ID, SECOND_TARGET_PET_ID)
        );

        then(clock).should(times(1)).instant();
        then(friendRequestRepository).should(times(1))
                .findActivePendingRelationships(
                        eq(SOURCE_PET_ID),
                        targetIds(FIRST_TARGET_PET_ID, SECOND_TARGET_PET_ID),
                        eq(NOW)
                );
        then(friendshipRepository).should(times(1))
                .findRelationships(
                        eq(SOURCE_PET_ID),
                        targetIds(FIRST_TARGET_PET_ID, SECOND_TARGET_PET_ID)
                );
        then(friendRequestRepository).shouldHaveNoMoreInteractions();
        then(friendshipRepository).shouldHaveNoMoreInteractions();
    }

    private PendingFriendRequestRelationshipRow pendingRow(
            Long requesterPetId,
            Long targetPetId
    ) {
        PendingFriendRequestRelationshipRow row =
                mock(PendingFriendRequestRelationshipRow.class);
        given(row.getRequesterPetId()).willReturn(requesterPetId);
        given(row.getTargetPetId()).willReturn(targetPetId);
        return row;
    }

    private FriendshipRelationshipRow friendshipRow(
            Long petLowId,
            Long petHighId
    ) {
        FriendshipRelationshipRow row =
                mock(FriendshipRelationshipRow.class);
        given(row.getPetLowId()).willReturn(petLowId);
        given(row.getPetHighId()).willReturn(petHighId);
        return row;
    }

    private Collection<Long> targetIds(Long... expectedTargetPetIds) {
        Set<Long> expected = Set.of(expectedTargetPetIds);
        return argThat(actual -> Set.copyOf(actual).equals(expected));
    }

    private void assertValidationFailed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(error ->
                        ((BusinessException) error).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
