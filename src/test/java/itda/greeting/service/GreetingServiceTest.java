package itda.greeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.dto.GreetingResponse;
import itda.greeting.repository.GreetingRepository;
import itda.media.domain.MediaStatus;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GreetingServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long FROM_PET_ID = 11L;
    private static final Long TO_USER_ID = 2L;
    private static final Long TO_PET_ID = 22L;
    private static final Long SETLOG_ID = 10L;
    private static final Long ROOM_ID = 100L;
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");

    @Mock
    private GreetingRepository greetingRepository;
    @Mock
    private SetlogRepository setlogRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock
    private ChatRoomService chatRoomService;
    @Mock
    private ChatMessageService chatMessageService;

    private GreetingService greetingService;

    @BeforeEach
    void setUp() {
        greetingService = new GreetingService(
                greetingRepository,
                setlogRepository,
                petRepository,
                activePetQueryService,
                blockRelationshipQueryService,
                chatRoomService,
                chatMessageService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsGreetingRoomAndFixedTextInOneFlow() {
        stubAllowedGreeting();
        Greeting saved = mock(Greeting.class);
        given(greetingRepository.existsByFromPet_IdAndToPet_Id(
                FROM_PET_ID,
                TO_PET_ID
        )).willReturn(false);
        given(greetingRepository
                .countByFromPet_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        FROM_PET_ID,
                        Instant.parse("2026-07-29T15:00:00Z"),
                        Instant.parse("2026-07-30T15:00:00Z")
                )).willReturn(0L);
        given(chatRoomService.ensureDirectRoom(
                FROM_PET_ID,
                TO_PET_ID,
                RoomOrigin.GREETING
        )).willReturn(new EnsureDirectRoomResult(ROOM_ID, true));
        given(greetingRepository.saveAndFlush(
                org.mockito.ArgumentMatchers.any(Greeting.class)
        )).willReturn(saved);
        given(saved.getId()).willReturn(200L);
        given(saved.getStatus()).willReturn(GreetingStatus.SENT);
        given(saved.getExpiresAt()).willReturn(NOW.plusSeconds(86_400));
        given(saved.getCreatedAt()).willReturn(NOW);

        GreetingResponse result = greetingService.send(USER_ID, SETLOG_ID);

        assertThat(result.greetingId()).isEqualTo(200L);
        assertThat(result.roomId()).isEqualTo(ROOM_ID);
        assertThat(result.fixedMessage())
                .isEqualTo(GreetingService.FIXED_MESSAGE);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        then(chatMessageService).should().sendGreetingText(
                org.mockito.ArgumentMatchers.eq(ROOM_ID),
                org.mockito.ArgumentMatchers.eq(FROM_PET_ID),
                argThat(request ->
                        request.clientMessageId().equals("greeting:11:22")
                                && request.body().equals(
                                GreetingService.FIXED_MESSAGE
                        )
                )
        );
    }

    @Test
    void duplicateDirectionIsPermanentlyRejected() {
        stubAllowedGreeting();
        given(greetingRepository.existsByFromPet_IdAndToPet_Id(
                FROM_PET_ID,
                TO_PET_ID
        )).willReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> greetingService.send(USER_ID, SETLOG_ID)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.GREETING_ALREADY_USED);
        then(chatRoomService).shouldHaveNoInteractions();
    }

    @Test
    void eleventhGreetingOnSeoulDayIsRejected() {
        stubAllowedGreeting();
        given(greetingRepository.existsByFromPet_IdAndToPet_Id(
                FROM_PET_ID,
                TO_PET_ID
        )).willReturn(false);
        given(greetingRepository
                .countByFromPet_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        FROM_PET_ID,
                        Instant.parse("2026-07-29T15:00:00Z"),
                        Instant.parse("2026-07-30T15:00:00Z")
                )).willReturn(10L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> greetingService.send(USER_ID, SETLOG_ID)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.GREETING_DAILY_LIMIT_EXCEEDED);
        then(chatRoomService).shouldHaveNoInteractions();
    }

    @Test
    void sameOwnerGreetingIsRejected() {
        Fixture fixture = stubAllowedGreeting();
        given(fixture.toOwner().getId()).willReturn(USER_ID);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> greetingService.send(USER_ID, SETLOG_ID)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.GREETING_SELF_FORBIDDEN);
        then(greetingRepository).should(never())
                .existsByFromPet_IdAndToPet_Id(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    private Fixture stubAllowedGreeting() {
        ActivePetContext activePet = new ActivePetContext(
                FROM_PET_ID,
                USER_ID,
                "나#A7K2",
                "나",
                null,
                false
        );
        Pet fromPet = mock(Pet.class);
        Pet toPet = mock(Pet.class);
        User toOwner = mock(User.class);
        Setlog setlog = mock(Setlog.class);

        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet);
        given(petRepository.findByIdForUpdate(FROM_PET_ID))
                .willReturn(Optional.of(fromPet));
        lenient().when(fromPet.getId()).thenReturn(FROM_PET_ID);
        given(setlogRepository.findVisibleSeedById(
                SETLOG_ID,
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).willReturn(Optional.of(setlog));
        given(setlog.getAuthorPet()).willReturn(toPet);
        lenient().when(toPet.getId()).thenReturn(TO_PET_ID);
        given(toPet.isActive()).willReturn(true);
        given(toPet.getOwner()).willReturn(toOwner);
        given(toOwner.getId()).willReturn(TO_USER_ID);
        lenient().when(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                TO_USER_ID
        )).thenReturn(false);

        return new Fixture(fromPet, toPet, toOwner, setlog);
    }

    private record Fixture(
            Pet fromPet,
            Pet toPet,
            User toOwner,
            Setlog setlog
    ) {
    }
}
