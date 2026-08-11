package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.domain.Friendship;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipCountRow;
import itda.friend.service.FriendRequestResponseAssembler.Snapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendRequestAcceptanceServiceTest {

    private static final Long REQUESTER_PET_ID = 10L;
    private static final Long TARGET_PET_ID = 20L;
    private static final Instant NOW =
            Instant.parse("2026-07-30T05:00:00Z");

    @Mock
    private FriendshipRepository friendshipRepository;
    @Mock
    private ChatRoomService chatRoomService;
    @Mock
    private FriendRequestResponseAssembler responseAssembler;

    private FriendRequestAcceptanceService service;

    @BeforeEach
    void setUp() {
        service = new FriendRequestAcceptanceService(
                friendshipRepository,
                chatRoomService,
                responseAssembler
        );
    }

    @Test
    void acceptsAtFortyNineAndFlushesBeforeChat() {
        FriendRequest pending = pending();
        FriendRequestResponse expected = mock(FriendRequestResponse.class);
        FriendshipCountRow requesterCount =
                count(REQUESTER_PET_ID, 49L);
        FriendshipCountRow targetCount =
                count(TARGET_PET_ID, 12L);
        given(friendshipRepository.countRelationshipsByPetIds(any()))
                .willReturn(List.of(
                        requesterCount,
                        targetCount
                ));
        given(chatRoomService.ensureDirectRoom(
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                RoomOrigin.FRIEND
        )).willReturn(new EnsureDirectRoomResult(99L, true));
        given(responseAssembler.accepted(any(), any(), any()))
                .willReturn(expected);

        FriendRequestResponse actual = service.accept(
                pending,
                TARGET_PET_ID,
                NOW
        );

        assertThat(actual).isSameAs(expected);
        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(pending.getRespondedAt()).isEqualTo(NOW);
        InOrder order = inOrder(
                friendshipRepository,
                chatRoomService,
                responseAssembler
        );
        order.verify(friendshipRepository).save(any(Friendship.class));
        order.verify(friendshipRepository).flush();
        order.verify(chatRoomService).ensureDirectRoom(
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                RoomOrigin.FRIEND
        );
        order.verify(responseAssembler).accepted(
                any(Snapshot.class),
                org.mockito.ArgumentMatchers.eq(TARGET_PET_ID),
                org.mockito.ArgumentMatchers.eq(99L)
        );
    }

    @Test
    void rejectsAtLimitWithoutChangingPendingRequest() {
        FriendRequest pending = pending();
        FriendshipCountRow requesterCount =
                count(REQUESTER_PET_ID, 50L);
        given(friendshipRepository.countRelationshipsByPetIds(any()))
                .willReturn(List.of(requesterCount));

        assertThatThrownBy(() -> service.accept(
                pending,
                TARGET_PET_ID,
                NOW
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FRIEND_LIMIT_EXCEEDED);

        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(pending.getRespondedAt()).isNull();
        verify(friendshipRepository, never()).save(any(Friendship.class));
        verify(friendshipRepository, never()).flush();
        verify(chatRoomService, never())
                .ensureDirectRoom(anyLong(), anyLong(), any());
        verify(responseAssembler, never()).accepted(any(), any(), any());
    }

    private FriendRequest pending() {
        return FriendRequest.createPending(
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
    }

    private FriendshipCountRow count(Long petId, Long friendCount) {
        FriendshipCountRow row = mock(FriendshipCountRow.class);
        given(row.getPetId()).willReturn(petId);
        given(row.getFriendCount()).willReturn(friendCount);
        return row;
    }
}
