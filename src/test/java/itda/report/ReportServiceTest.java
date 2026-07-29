package itda.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.report.domain.ReportReason;
import itda.report.dto.ReportCreateRequest;
import itda.report.repository.ReportRepository;
import itda.report.service.ReportCreateTransactionService;
import itda.report.service.ReportService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private ChatRoomParticipantRepository chatRoomParticipantRepository;
    @Mock
    private ChatRoomRepository chatRoomRepository;
    @Mock
    private PetDisplayQueryService petDisplayQueryService;
    @Mock
    private ReportCreateTransactionService reportCreateTransactionService;

    @InjectMocks
    private ReportService reportService;

    private static final long USER_ID = 1L;
    private static final long ACTOR_PET_ID = 11L;
    private static final long REPORTED_PET_ID = 22L;
    private static final long ROOM_ID = 100L;

    @BeforeEach
    void setUpDirectRoom() {
        when(activePetQueryService.requireActivePet(USER_ID))
                .thenReturn(new ActivePetContext(
                        ACTOR_PET_ID, USER_ID, "actor#0001", "actor", null, false));
        when(chatRoomParticipantRepository
                .existsByRoomIdAndPetIdAndLeftAtIsNull(ROOM_ID, ACTOR_PET_ID))
                .thenReturn(true);

        ChatRoom room = mock(ChatRoom.class);
        when(room.getPetLowId()).thenReturn(ACTOR_PET_ID);
        when(room.getPetHighId()).thenReturn(REPORTED_PET_ID);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    @Test
    @DisplayName("상대 Pet 미존재만 CHAT_ROOM_NOT_FOUND로 은닉한다")
    void petNotFoundIsHiddenAsChatRoomNotFound() {
        when(petDisplayQueryService.getPetDisplaySummary(REPORTED_PET_ID))
                .thenThrow(new BusinessException(ErrorCode.PET_NOT_FOUND));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reportService.createReport(USER_ID, request()));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("상대 Pet 조회의 다른 업무 오류는 404로 숨기지 않는다")
    void nonPetNotFoundBusinessErrorIsPreserved() {
        BusinessException original = new BusinessException(ErrorCode.INTERNAL_ERROR);
        when(petDisplayQueryService.getPetDisplaySummary(REPORTED_PET_ID))
                .thenThrow(original);

        BusinessException thrown = assertThrows(
                BusinessException.class,
                () -> reportService.createReport(USER_ID, request()));

        assertSame(original, thrown);
    }

    private ReportCreateRequest request() {
        return new ReportCreateRequest(ROOM_ID, ReportReason.SPAM, null);
    }
}
