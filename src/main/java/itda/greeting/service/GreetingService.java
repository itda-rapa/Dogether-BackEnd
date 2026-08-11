package itda.greeting.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.Greeting;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GreetingService {

    public static final String FIXED_MESSAGE =
            "안녕하세요! 같이 놀아요.";

    private static final int DAILY_LIMIT = 10;
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<MediaStatus> PLAYABLE_MEDIA_STATUSES =
            List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final GreetingRepository greetingRepository;
    private final SetlogRepository setlogRepository;
    private final PetRepository petRepository;
    private final ActivePetQueryService activePetQueryService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final Clock clock;

    @Autowired
    public GreetingService(
            GreetingRepository greetingRepository,
            SetlogRepository setlogRepository,
            PetRepository petRepository,
            ActivePetQueryService activePetQueryService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ChatRoomService chatRoomService,
            ChatMessageService chatMessageService
    ) {
        this(
                greetingRepository,
                setlogRepository,
                petRepository,
                activePetQueryService,
                blockRelationshipQueryService,
                chatRoomService,
                chatMessageService,
                Clock.systemUTC()
        );
    }

    GreetingService(
            GreetingRepository greetingRepository,
            SetlogRepository setlogRepository,
            PetRepository petRepository,
            ActivePetQueryService activePetQueryService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ChatRoomService chatRoomService,
            ChatMessageService chatMessageService,
            Clock clock
    ) {
        this.greetingRepository = greetingRepository;
        this.setlogRepository = setlogRepository;
        this.petRepository = petRepository;
        this.activePetQueryService = activePetQueryService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.chatRoomService = chatRoomService;
        this.chatMessageService = chatMessageService;
        this.clock = clock;
    }

    /**
     * Creates the permanent directional Greeting history, ensures a DIRECT
     * room and stores the server-defined first TEXT in one transaction.
     */
    @Transactional
    public GreetingResponse send(Long userId, Long setlogId) {
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(userId);
        Pet fromPet = petRepository.findByIdForUpdate(activePet.petId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED)
                );
        Setlog setlog = setlogRepository.findVisibleSeedById(
                        setlogId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES
                )
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );
        Pet toPet = setlog.getAuthorPet();
        requireGreetingAllowed(activePet, toPet);

        if (greetingRepository.existsByFromPet_IdAndToPet_Id(
                fromPet.getId(),
                toPet.getId()
        )) {
            throw new BusinessException(ErrorCode.GREETING_ALREADY_USED);
        }

        Instant now = clock.instant();
        Instant dayStart = startOfSeoulDay(now);
        Instant nextDayStart = LocalDate.ofInstant(now, SEOUL)
                .plusDays(1)
                .atStartOfDay(SEOUL)
                .toInstant();
        long greetingsToday =
                greetingRepository
                        .countByFromPet_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                                fromPet.getId(),
                                dayStart,
                                nextDayStart
                        );
        if (greetingsToday >= DAILY_LIMIT) {
            throw new BusinessException(
                    ErrorCode.GREETING_DAILY_LIMIT_EXCEEDED
            );
        }

        EnsureDirectRoomResult room = chatRoomService.ensureDirectRoom(
                fromPet.getId(),
                toPet.getId(),
                RoomOrigin.GREETING
        );
        Greeting greeting = greetingRepository.saveAndFlush(
                Greeting.send(
                        fromPet,
                        toPet,
                        setlog,
                        room.roomId(),
                        now.plus(RESPONSE_TTL)
                )
        );
        chatMessageService.sendGreetingText(
                room.roomId(),
                fromPet.getId(),
                new ChatMessageCreateRequest(
                        greetingMessageKey(fromPet.getId(), toPet.getId()),
                        FIXED_MESSAGE
                )
        );

        return new GreetingResponse(
                greeting.getId(),
                room.roomId(),
                greeting.getStatus(),
                FIXED_MESSAGE,
                greeting.getExpiresAt(),
                greeting.getCreatedAt()
        );
    }

    private void requireGreetingAllowed(
            ActivePetContext activePet,
            Pet toPet
    ) {
        if (!toPet.isActive() || toPet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SETLOG_NOT_FOUND);
        }
        Long targetOwnerId = toPet.getOwner().getId();
        if (activePet.ownerUserId().equals(targetOwnerId)) {
            throw new BusinessException(ErrorCode.GREETING_SELF_FORBIDDEN);
        }
        if (blockRelationshipQueryService.existsBlockBetween(
                activePet.ownerUserId(),
                targetOwnerId
        )) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
    }

    private Instant startOfSeoulDay(Instant now) {
        return LocalDate.ofInstant(now, SEOUL)
                .atStartOfDay(SEOUL)
                .toInstant();
    }

    private String greetingMessageKey(
            Long fromPetId,
            Long toPetId
    ) {
        return "greeting:%d:%d".formatted(fromPetId, toPetId);
    }
}
