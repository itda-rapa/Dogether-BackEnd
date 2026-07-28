package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.RoomType;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomParticipantRepository participantRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    void shouldNormalizePetPair() {
        // pair (5, 2) normalizes to (2, 5)
        ChatRoom mockRoom = mock(ChatRoom.class);
        when(mockRoom.getId()).thenReturn(1L);

        when(chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(
                eq(RoomType.DIRECT), eq(2L), eq(5L)))
                .thenReturn(Optional.empty(), Optional.of(mockRoom));
        when(chatRoomRepository.insertDirectRoomOnConflict(
                anyString(), anyString(), anyString(), eq(2L), eq(5L)))
                .thenReturn(1);

        EnsureDirectRoomResult result = chatRoomService.ensureDirectRoom(5L, 2L, RoomOrigin.GREETING);

        assertThat(result.roomId()).isEqualTo(1L);
        assertThat(result.isNew()).isTrue();
    }

    @Test
    void shouldRejectIdenticalPetIds() {
        assertThatThrownBy(() ->
                chatRoomService.ensureDirectRoom(10L, 10L, RoomOrigin.GREETING))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_SAME_PET_FORBIDDEN);
    }

    @Test
    void shouldReturnExistingRoomWhenAlreadyPresent() {
        ChatRoom existing = mock(ChatRoom.class);
        when(existing.getId()).thenReturn(42L);

        when(chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(
                eq(RoomType.DIRECT), eq(1L), eq(2L)))
                .thenReturn(Optional.of(existing));

        EnsureDirectRoomResult result = chatRoomService.ensureDirectRoom(1L, 2L, RoomOrigin.GREETING);

        assertThat(result.roomId()).isEqualTo(42L);
        assertThat(result.isNew()).isFalse();

        verify(chatRoomRepository, never()).insertDirectRoomOnConflict(
                anyString(), anyString(), anyString(), anyLong(), anyLong());
        verify(participantRepository, never()).save(any());
    }

    @Test
    void shouldRegisterParticipantsForNewRoom() {
        ChatRoom mockRoom = mock(ChatRoom.class);
        when(mockRoom.getId()).thenReturn(7L);

        when(chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(
                eq(RoomType.DIRECT), eq(1L), eq(2L)))
                .thenReturn(Optional.empty(), Optional.of(mockRoom));                    // fast-path miss → re-lookup
        when(chatRoomRepository.insertDirectRoomOnConflict(
                anyString(), anyString(), anyString(), eq(1L), eq(2L)))
                .thenReturn(1);                                                        // writer thread wins

        EnsureDirectRoomResult result = chatRoomService.ensureDirectRoom(1L, 2L, RoomOrigin.GREETING);

        assertThat(result.roomId()).isEqualTo(7L);
        assertThat(result.isNew()).isTrue();
        // A DIRECT room registers both pets as participants
        verify(participantRepository, times(2)).save(any());
    }

    @Test
    void shouldNotRegisterParticipantsWhenExistingRoomFoundOnReLookup() {
        ChatRoom mockRoom = mock(ChatRoom.class);
        when(mockRoom.getId()).thenReturn(10L);

        when(chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(
                eq(RoomType.DIRECT), eq(1L), eq(2L)))
                .thenReturn(Optional.empty(), Optional.of(mockRoom));                    // fast-path miss → re-lookup
        when(chatRoomRepository.insertDirectRoomOnConflict(
                anyString(), anyString(), anyString(), eq(1L), eq(2L)))
                .thenReturn(0);                                                        // conflict — another writer won

        EnsureDirectRoomResult result = chatRoomService.ensureDirectRoom(1L, 2L, RoomOrigin.GREETING);

        assertThat(result.roomId()).isEqualTo(10L);
        assertThat(result.isNew()).isFalse();

        // This writer must NOT register participants
        verify(participantRepository, never()).save(any());
    }
}