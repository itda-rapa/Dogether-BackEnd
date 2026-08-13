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
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.media.domain.MediaStatus;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
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
    private final InteractionPairLockService interactionPairLockService;
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
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ChatRoomService chatRoomService,
            ChatMessageService chatMessageService
    ) {
        this(
                greetingRepository,
                setlogRepository,
                petRepository,
                activePetQueryService,
                interactionPairLockService,
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
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ChatRoomService chatRoomService,
            ChatMessageService chatMessageService,
            Clock clock
    ) {
        this.greetingRepository = greetingRepository;
        this.setlogRepository = setlogRepository;
        this.petRepository = petRepository;
        this.activePetQueryService = activePetQueryService;
        this.interactionPairLockService = interactionPairLockService;
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
        Long targetPetId = setlogRepository.findAuthorPetIdById(setlogId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );
        InteractionPairContext pair =
                interactionPairLockService.lockInteractionPair(
                        activePet.petId(),
                        targetPetId
                );
        validateLockedPair(userId, activePet, pair);

        if (activePet.ownerUserId().equals(pair.targetUser().userId())) {
            throw new BusinessException(ErrorCode.GREETING_SELF_FORBIDDEN);
        }
        if (blockRelationshipQueryService.existsBlockBetween(
                pair.sourceUser().userId(),
                pair.targetUser().userId()
        )) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }

        Setlog setlog = setlogRepository.findInteractableByIdForUpdate(
                        setlogId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES
                )
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );
        if (!Objects.equals(setlog.getAuthorPet().getId(), targetPetId)) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        Pet fromPet = petRepository.findById(activePet.petId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED)
                );
        Pet toPet = setlog.getAuthorPet();

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

    private void validateLockedPair(
            Long userId,
            ActivePetContext activePet,
            InteractionPairContext pair
    ) {
        if (!Objects.equals(userId, pair.sourceUser().userId())
                || !Objects.equals(
                        activePet.ownerUserId(),
                        pair.sourceUser().userId()
                )) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (pair.sourceUser().accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(
                        pair.sourceUser().activePetId(),
                        activePet.petId()
                )
                || pair.sourcePet().status() != PetStatus.ACTIVE
                || pair.sourcePet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
        if (pair.targetUser().accountStatus() != AccountStatus.ACTIVE
                || pair.targetPet().status() != PetStatus.ACTIVE
                || pair.targetPet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.SETLOG_NOT_FOUND);
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
