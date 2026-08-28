package itda.meetingcard.service;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingParticipant;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.dto.response.MeetingCardListResponse;
import itda.meetingcard.dto.response.MeetingCardResponse;
import itda.meetingcard.dto.response.OpenChatDraftParticipantResponse;
import itda.meetingcard.repository.CardDraftRepository;
import itda.meetingcard.repository.CardDraftParticipantRepository;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.meetingcard.support.MeetingCardCursorCodec;
import itda.meetingcard.support.MeetingCardCursorCodec.CursorPayload;
import itda.meetingverification.repository.MeetingRepository;
import itda.chat.dto.response.CursorPage;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.route.repository.RouteRequestRepository;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 약속 카드 확정과 조회.
 *
 * <p>확정은 카드·참여자·CARD 메시지가 전부 성립하거나 전부 없어야 한다. 카드만 남고 메시지가
 * 없으면 사용자는 약속이 잡힌 사실을 채팅에서 볼 수 없고, 메시지만 남으면 열 수 없는 카드를
 * 가리킨다.
 */
@Service
public class MeetingCardService {

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;

    private final ActivePetQueryService activePetQueryService;
    private final ChatQueryService chatQueryService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final MeetingCardRepository meetingCardRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final CardDraftRepository cardDraftRepository;
    private final CardDraftParticipantRepository cardDraftParticipantRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final MeetingRepository meetingRepository;
    private final Clock clock;
    private RouteRequestRepository routeRequestRepository;
    private ChatRoomParticipantRepository chatRoomParticipantRepository;
    private PetDisplayQueryService petDisplayQueryService;

    @Autowired
    void setRouteRequestRepository(RouteRequestRepository routeRequestRepository) {
        this.routeRequestRepository = routeRequestRepository;
    }

    @Autowired
    void setChatRoomParticipantRepository(
            ChatRoomParticipantRepository chatRoomParticipantRepository) {
        this.chatRoomParticipantRepository = chatRoomParticipantRepository;
    }

    @Autowired
    void setPetDisplayQueryService(PetDisplayQueryService petDisplayQueryService) {
        this.petDisplayQueryService = petDisplayQueryService;
    }

    // 프로젝트 관행대로 주 생성자가 Clock 을 받고 편의 생성자가 기본값을 넘긴다.
    // GreetingService, FriendRelationshipQueryService, CardDraftService 와 같은 형태다.
    @Autowired
    public MeetingCardService(ActivePetQueryService activePetQueryService,
                              ChatQueryService chatQueryService,
                              ChatRoomRepository chatRoomRepository,
                              ChatMessageService chatMessageService,
                              MeetingCardRepository meetingCardRepository,
                              MeetingParticipantRepository meetingParticipantRepository,
                              CardDraftRepository cardDraftRepository,
                              CardDraftParticipantRepository cardDraftParticipantRepository,
                              InteractionPairLockService interactionPairLockService,
                              MeetingRepository meetingRepository) {
        this(activePetQueryService, chatQueryService, chatRoomRepository, chatMessageService,
                meetingCardRepository, meetingParticipantRepository, cardDraftRepository,
                cardDraftParticipantRepository,
                interactionPairLockService, meetingRepository, Clock.systemUTC());
    }

    MeetingCardService(ActivePetQueryService activePetQueryService,
                       ChatQueryService chatQueryService,
                       ChatRoomRepository chatRoomRepository,
                       ChatMessageService chatMessageService,
                       MeetingCardRepository meetingCardRepository,
                       MeetingParticipantRepository meetingParticipantRepository,
                       CardDraftRepository cardDraftRepository,
                       CardDraftParticipantRepository cardDraftParticipantRepository,
                       InteractionPairLockService interactionPairLockService,
                       MeetingRepository meetingRepository,
                       Clock clock) {
        this.activePetQueryService = activePetQueryService;
        this.chatQueryService = chatQueryService;
        this.chatRoomRepository = chatRoomRepository;
        this.chatMessageService = chatMessageService;
        this.meetingCardRepository = meetingCardRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.cardDraftRepository = cardDraftRepository;
        this.cardDraftParticipantRepository = cardDraftParticipantRepository;
        this.interactionPairLockService = interactionPairLockService;
        this.meetingRepository = meetingRepository;
        this.clock = clock;
    }

    @Transactional
    public MeetingCardResponse confirm(Long userId, MeetingCardCreateRequest request) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // 방 없음·참가자 아님·차단을 모두 404 로 수렴시킨다. 채팅과 같은 검사를 재사용한다.
        chatQueryService.requireParticipant(request.roomId(), actor.petId());

        ChatRoom room = chatRoomRepository.findById(request.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<Long> participantPetIds;
        Long sourceDraftId;
        if (room.isOpenChat()) {
            sourceDraftId = resolveDraftId(request, actor.petId(), true);
            if (sourceDraftId == null && request.routeRequestId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            List<Long> snapshotPetIds = sourceDraftId == null
                    ? chatRoomParticipantRepository.findByRoomId(request.roomId()).stream()
                            .filter(participant -> participant.getLeftAt() == null)
                            .map(participant -> participant.getPetId())
                            .distinct()
                            .toList()
                    : cardDraftParticipantRepository
                            .findByCardDraftIdOrderByIdAsc(sourceDraftId).stream()
                            .map(participant -> participant.getPetId())
                            .distinct()
                            .toList();
            participantPetIds = selectOpenChatParticipants(
                    request, snapshotPetIds, actor.petId());
            // AI 초안은 합의할 상대가 한 명 이상 필요하지만, 이미 공유된 경로에서
            // 만드는 약속 카드는 방에 혼자 남은 시점에도 먼저 발급할 수 있다.
            // 이후 초대된 참여자는 채팅에서 카드를 확인할 수 있다.
            int minimumParticipants = request.routeRequestId() == null ? 2 : 1;
            if (participantPetIds.size() < minimumParticipants
                    || !participantPetIds.contains(actor.petId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else {
            participantPetIds = directPair(room, actor.petId());
            Long counterpartPetId = participantPetIds.get(0).equals(actor.petId())
                    ? participantPetIds.get(1) : participantPetIds.get(0);
            InteractionPairContext lockedPair = interactionPairLockService.lockInteractionPair(
                    actor.petId(), counterpartPetId);
            requireLockedActor(userId, actor, lockedPair);
            chatQueryService.requireParticipant(request.roomId(), actor.petId());
            chatQueryService.requireGreetingReplyCompleted(request.roomId());
            sourceDraftId = resolveDraftId(request, actor.petId(), false);
        }

        MeetingCard card = meetingCardRepository.save(new MeetingCard(
                request.roomId(),
                actor.petId(),
                sourceDraftId,
                request.cardType(),
                request.placeText(),
                request.meetAt(),
                validatedRouteId(userId, request),
                resolvedParticipantCount(request, room, participantPetIds)
        ));

        for (Long petId : participantPetIds) {
            meetingParticipantRepository.save(new MeetingParticipant(card.getId(), petId));
        }

        // 같은 트랜잭션이므로 메시지 발행이 실패하면 카드와 참여자도 함께 롤백된다.
        // clientMessageId 는 카드 id 로 결정적이라 재시도해도 메시지가 두 건 생기지 않는다.
        chatMessageService.postCard(
                request.roomId(),
                actor.petId(),
                card.getId(),
                "meeting-card:" + card.getId() + ":created");

        return toResponse(card, participantPetIds);
    }

    private int resolvedParticipantCount(MeetingCardCreateRequest request, ChatRoom room,
                                         List<Long> participantPetIds) {
        int currentParticipants = participantPetIds.size();
        int requested = request.participantCount() == null
                ? currentParticipants : request.participantCount();
        if (requested != currentParticipants) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return requested;
    }

    private java.util.UUID validatedRouteId(Long userId, MeetingCardCreateRequest request) {
        if (request.routeRequestId() == null) {
            return null;
        }
        if (routeRequestRepository == null || !routeRequestRepository.isAvailableForMeeting(
                request.routeRequestId(), userId, request.roomId())) {
            throw new BusinessException(ErrorCode.ROUTE_SHARE_FORBIDDEN);
        }
        return request.routeRequestId();
    }

    @Transactional(readOnly = true)
    public MeetingCardResponse get(Long userId, long cardId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        MeetingCard card = meetingCardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        // 권한은 방 참가자가 아니라 카드 참여자 기준이다. 방을 떠난 Pet 도 자기가 참여한
        // 카드는 볼 수 있어야 한다.
        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }

        // 차단은 카드가 만들어진 뒤에 걸릴 수 있으므로 조회 시점에 다시 본다.
        chatQueryService.requireParticipant(card.getRoomId(), actor.petId());

        return toResponse(card, meetingParticipantRepository.findPetIdsByMeetingCardId(cardId));
    }

    @Transactional(readOnly = true)
    public MeetingCardListResponse listMine(
            Long userId,
            String rawStatus,
            String cursor,
            Integer rawLimit
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        int limit = validateListLimit(rawLimit);
        String status = normalizeStatus(rawStatus);
        CursorPayload payload = MeetingCardCursorCodec.decode(cursor);

        List<MeetingCard> cards = new ArrayList<>(meetingCardRepository.findVisibleCards(
                actor.petId(),
                status,
                payload == null ? null : payload.meetAt(),
                payload == null ? null : payload.cardId(),
                limit + 1
        ));
        boolean hasNext = cards.size() > limit;
        if (hasNext) {
            cards = new ArrayList<>(cards.subList(0, limit));
        }

        Map<Long, List<Long>> participantIdsByCard = new LinkedHashMap<>();
        if (!cards.isEmpty()) {
            cards.forEach(card -> participantIdsByCard.put(card.getId(), new ArrayList<>()));
            meetingParticipantRepository.findByMeetingCardIdInOrderByMeetingCardIdAscPetIdAsc(
                            participantIdsByCard.keySet())
                    .forEach(participant -> participantIdsByCard
                            .get(participant.getMeetingCardId())
                            .add(participant.getPetId()));
        }

        List<MeetingCardResponse> items = cards.stream()
                .map(card -> toResponse(card, participantIdsByCard.get(card.getId())))
                .toList();
        String nextCursor = hasNext && !cards.isEmpty()
                ? MeetingCardCursorCodec.encode(
                        cards.get(cards.size() - 1).getId(),
                        cards.get(cards.size() - 1).getMeetAt())
                : null;

        return new MeetingCardListResponse(items, new CursorPage(nextCursor, hasNext));
    }

    /**
     * 약속 카드 취소. 참여 Pet 양쪽 모두 취소할 수 있다.
     *
     * <p>동시 취소가 이 메서드의 핵심이다. 잠금 없이 조회하면 두 트랜잭션이 모두
     * {@code OPEN} 을 읽어 둘 다 성공하고, 결정적 clientMessageId 때문에 SYSTEM 메시지는
     * 멱등 처리되어 아무도 실패하지 않는다. 그러면 패자에게 409 를 줘야 하는 계약이
     * 조용히 깨진다. 그래서 행 잠금을 먼저 잡는다.
     */
    @Transactional
    public MeetingCardResponse cancel(Long userId, long cardId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        // GPS submit은 Pair(User -> Pet) 뒤에 Card를 잠근다. 여기서 Card를 먼저 잠그고
        // card.cancel()의 canceledByPet FK가 flush되면, submit과 Card <-> Pet 역순
        // 대기가 생겨 PostgreSQL deadlock이 난다. identity projection은 영속성 컨텍스트에
        // 엔티티를 넣지 않으므로 존재만 먼저 확인한 뒤 동일한 Pair -> Card 순서를 지킬 수 있다.
        meetingCardRepository.findIdentityById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        // 취소가 실제로 FK를 참조하는 것은 취소자 Pet 하나다. 같은 Pet을 양쪽 인자로 주면
        // InteractionPairLockService의 표준 User -> Pet 순서를 그대로 재사용하면서 그 행을
        // Card보다 먼저 잠근다. DIRECT GPS 제출이 두 Pet을 잠근 상태와도 순서가 호환된다.
        InteractionPairContext lockedActor = interactionPairLockService.lockInteractionPair(
                actor.petId(), actor.petId());
        requireLockedActor(userId, actor, lockedActor);

        // Pair lock 뒤에 Card를 잡는다. 이 앞에서 findById로 카드를 읽으면 그 엔티티가
        // 영속성 컨텍스트에 들어가고, 뒤이은 SELECT ... FOR UPDATE가 실행돼도 Hibernate는
        // 이미 관리 중인 인스턴스를 상태 갱신 없이 그대로 돌려준다. 그러면 패자가 잠금을
        // 얻은 뒤에도 캐시에 남은 OPEN을 보고 취소에 성공한다.
        MeetingCard card = meetingCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND));

        if (!meetingParticipantRepository.existsByMeetingCardIdAndPetId(cardId, actor.petId())) {
            // 참여자가 아니면 카드 존재 자체를 숨긴다. 취소 권한 없음(403)을 주면
            // 남의 카드 id 를 훑어 존재를 알아낼 수 있다.
            throw new BusinessException(ErrorCode.MEETING_CARD_NOT_FOUND);
        }

        chatQueryService.requireParticipant(card.getRoomId(), actor.petId());

        // 확정 Meeting 이 있는 카드는 수동 취소를 금지한다. GPS 확정·취소는 모두 이 카드의
        // PESSIMISTIC_WRITE 경계에서 직렬화되므로 여기서 확정 여부를 안전하게 판단한다.
        if (meetingRepository.existsByMeetingCardId(cardId)) {
            throw new BusinessException(ErrorCode.MEETING_ALREADY_CONFIRMED);
        }

        // 이미 취소면 MEETING_CARD_ALREADY_CANCELED(409). 동시 취소의 패자가 여기로 온다.
        card.cancel(actor.petId(), clock.instant());

        // 같은 트랜잭션이므로 메시지 발행이 실패하면 취소도 롤백된다. clientMessageId 가
        // 카드 id 로 결정적이라 SYSTEM 메시지는 카드당 한 건을 넘지 않는다.
        chatMessageService.postSystem(
                card.getRoomId(),
                "약속이 취소되었습니다.",
                "meeting-card:" + cardId + ":canceled");

        return toResponse(card, meetingParticipantRepository.findPetIdsByMeetingCardId(cardId));
    }

    private MeetingCardResponse toResponse(MeetingCard card, List<Long> participantPetIds) {
        if (petDisplayQueryService == null || participantPetIds == null || participantPetIds.isEmpty()) {
            return MeetingCardResponse.of(card,
                    participantPetIds == null ? List.of() : participantPetIds);
        }
        Map<Long, PetDisplaySummary> summaries =
                petDisplayQueryService.getPetDisplaySummaries(participantPetIds);
        List<OpenChatDraftParticipantResponse> participants = participantPetIds.stream()
                .map(summaries::get)
                .filter(Objects::nonNull)
                .map(OpenChatDraftParticipantResponse::from)
                .toList();
        return MeetingCardResponse.of(card, participantPetIds, participants);
    }

    /**
     * DIRECT 방의 두 Pet. GROUP 방은 카드 대상이 아니고, pair 밖의 Pet 이 참가자 테이블에
     * 섞여 들어와도 카드를 만들지 못하게 막는다.
     */
    private List<Long> directPair(ChatRoom room, long actorPetId) {
        Long low = room.getPetLowId();
        Long high = room.getPetHighId();
        if (low == null || high == null) {
            throw new BusinessException(ErrorCode.MEETING_CARD_ROOM_REQUIRED);
        }
        if (!low.equals(actorPetId) && !high.equals(actorPetId)) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        return List.of(low, high);
    }

    /**
     * 초안을 거쳐 확정한 경우 그 초안이 요청자 것이고 같은 방인지 확인한다.
     *
     * <p>남의 초안이나 다른 방 초안으로 카드를 만들 수 있으면 초안에 담긴 대화 추출 결과가
     * 엉뚱한 방으로 새어 나간다. 중복 사용은 DB 의 {@code uk_meeting_card_source_draft} 가
     * 최종 방어선이고 여기서는 사용자에게 400 을 주기 위해 먼저 검사한다.
     */
    private Long resolveDraftId(
            MeetingCardCreateRequest request,
            long actorPetId,
            boolean openChat
    ) {
        if (request.draftId() == null) {
            return null;
        }
        CardDraft draft = cardDraftRepository.findById(request.draftId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        boolean ownsDraft = draft.getRequestedByPetId().equals(actorPetId);
        boolean participatesInDraft = openChat
                && cardDraftParticipantRepository.existsByCardDraftIdAndPetId(
                        request.draftId(), actorPetId);
        if ((!ownsDraft && !participatesInDraft)
                || !draft.getRoomId().equals(request.roomId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (meetingCardRepository.existsBySourceDraftId(request.draftId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return request.draftId();
    }

    private boolean chatRoomParticipantIsActive(long roomId, long petId) {
        return chatQueryService.isActiveParticipant(roomId, petId);
    }

    private List<Long> selectOpenChatParticipants(
            MeetingCardCreateRequest request,
            List<Long> snapshotPetIds,
            long actorPetId
    ) {
        List<Long> submitted = request.participantPetIds();
        List<Long> selected = submitted == null || submitted.isEmpty() ? snapshotPetIds : submitted;
        if (selected.stream().anyMatch(Objects::isNull)
                || selected.size() != selected.stream().distinct().count()
                || !snapshotPetIds.containsAll(selected)
                || !selected.contains(actorPetId)
                || selected.stream().anyMatch(
                        petId -> !chatRoomParticipantIsActive(request.roomId(), petId))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return List.copyOf(selected);
    }

    private void requireLockedActor(
            Long userId,
            ActivePetContext actor,
            InteractionPairContext lockedPair
    ) {
        if (!Objects.equals(lockedPair.sourceUser().userId(), userId)
                || !Objects.equals(actor.ownerUserId(), userId)
                || !Objects.equals(lockedPair.sourcePet().ownerUserId(), userId)
                || !Objects.equals(lockedPair.sourceUser().activePetId(), actor.petId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (lockedPair.sourceUser().accountStatus() != AccountStatus.ACTIVE
                || lockedPair.sourcePet().status() != PetStatus.ACTIVE
                || lockedPair.sourcePet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }

    private int validateListLimit(Integer rawLimit) {
        int limit = rawLimit == null ? DEFAULT_LIST_LIMIT : rawLimit;
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return limit;
    }

    private String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return MeetingCardStatus.valueOf(rawStatus).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
