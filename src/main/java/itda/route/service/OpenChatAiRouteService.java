package itda.route.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
import itda.chat.domain.RoomStatus;
import itda.chat.domain.SenderType;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.ai.MeetingDraftAiProperties;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.route.domain.RouteActivityType;
import itda.route.domain.RouteNodeRole;
import itda.route.domain.RoutePriorityType;
import itda.route.dto.NearestRouteNodeResponse;
import itda.route.dto.OpenChatAiRouteAcceptedResponse;
import itda.route.dto.RouteAcceptedResponse;
import itda.route.dto.RouteCreateRequest;
import itda.route.dto.RoundTripRouteCreateRequest;
import itda.route.repository.AiChatRouteJobRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OpenChatAiRouteService {

    private static final int MAX_MESSAGES = 30;
    private static final int MAX_WAYPOINTS = 20;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ActivePetQueryService activePetQueryService;
    private final ChatRoomRepository roomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final RouteService routeService;
    private final RestClient kakaoClient;
    private final RestClient aiClient;
    private final AiChatRouteJobRepository jobRepository;
    private final Clock clock;

    @Autowired
    public OpenChatAiRouteService(
            ActivePetQueryService activePetQueryService,
            ChatRoomRepository roomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            RouteService routeService,
            @Qualifier("kakaoRestClient") RestClient kakaoClient,
            AiChatRouteJobRepository jobRepository,
            MeetingDraftAiProperties aiProperties
    ) {
        this(activePetQueryService, roomRepository, participantRepository, messageRepository,
                routeService, kakaoClient, buildAiClient(aiProperties.baseUrl(),
                        aiProperties.timeout()), jobRepository, Clock.systemUTC());
    }

    OpenChatAiRouteService(
            ActivePetQueryService activePetQueryService,
            ChatRoomRepository roomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            RouteService routeService,
            RestClient kakaoClient,
            RestClient aiClient,
            AiChatRouteJobRepository jobRepository,
            Clock clock
    ) {
        this.activePetQueryService = activePetQueryService;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.routeService = routeService;
        this.kakaoClient = kakaoClient;
        this.aiClient = aiClient;
        this.jobRepository = jobRepository;
        this.clock = clock;
    }

    public OpenChatAiRouteAcceptedResponse create(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        var room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE
                || !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                        roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        }

        List<ChatMessage> newestFirst = messageRepository.findLatestPetTextMessages(
                roomId, SenderType.PET, MessageType.TEXT, PageRequest.of(0, MAX_MESSAGES));
        if (newestFirst.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_ROUTE_INSUFFICIENT_CONTEXT);
        }
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        AiRouteResponse inferred;
        try {
            inferred = aiClient.post().uri("/api/v2/routes/extract")
                    .body(new AiRouteRequest(String.valueOf(roomId), chronological.stream()
                            .map(message -> new AiMessage(
                                    String.valueOf(message.getSenderPetId()),
                                    message.getBody(),
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                            message.getCreatedAt().atZone(SEOUL))))
                            .toList()))
                    .retrieve()
                    .body(AiRouteResponse.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_ROUTE_REQUEST_FAILED);
        }

        NormalizedPlan plan = normalize(inferred);
        NearestRouteNodeResponse start = resolve(plan.start(), RouteNodeRole.START,
                plan.activityType());
        RouteAcceptedResponse accepted;
        if ("ROUND_TRIP".equals(plan.routeMode())) {
            long targetDistanceMeters = plan.targetDistanceKm().multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            accepted = routeService.createRoundTrip(userId, new RoundTripRouteCreateRequest(
                    start.nodeId(), targetDistanceMeters, plan.activityType(),
                    RoutePriorityType.GREEN, defaultSpeed(plan.activityType()),
                    Instant.now(clock).plusSeconds(60)));
        } else {
            NearestRouteNodeResponse destination = resolve(plan.destination(),
                    RouteNodeRole.DESTINATION, plan.activityType());
            List<Long> waypointNodeIds = plan.waypoints().stream()
                    .map(query -> resolve(query, RouteNodeRole.WAYPOINT, plan.activityType()).nodeId())
                    .toList();

            LinkedHashSet<Long> distinct = new LinkedHashSet<>();
            distinct.add(start.nodeId());
            distinct.addAll(waypointNodeIds);
            distinct.add(destination.nodeId());
            if (distinct.size() != waypointNodeIds.size() + 2) {
                throw new BusinessException(ErrorCode.AI_ROUTE_PLACE_NOT_FOUND);
            }
            accepted = routeService.create(userId, new RouteCreateRequest(
                    start.nodeId(), waypointNodeIds, destination.nodeId(), plan.activityType(),
                    RoutePriorityType.GREEN, defaultSpeed(plan.activityType()),
                    Instant.now(clock).plusSeconds(60)));
        }
        jobRepository.create(accepted.requestId(), roomId, userId, actor.petId());
        return new OpenChatAiRouteAcceptedResponse(
                accepted.requestId(), accepted.status(), plan.routeMode(), plan.activityType(),
                plan.start(), plan.waypoints(), plan.destination(), plan.targetDistanceKm());
    }

    private NormalizedPlan normalize(AiRouteResponse response) {
        if (response == null || !"READY".equalsIgnoreCase(response.status())) {
            throw new BusinessException(ErrorCode.AI_ROUTE_INSUFFICIENT_CONTEXT);
        }
        RouteActivityType activityType;
        try {
            activityType = RouteActivityType.valueOf(Objects.requireNonNullElse(
                    response.activityType(), "").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AI_ROUTE_INSUFFICIENT_CONTEXT);
        }
        String start = query(response.start());
        String destination = query(response.destination());
        String routeMode = Objects.requireNonNullElse(response.routeMode(), "").trim()
                .toUpperCase(Locale.ROOT);
        if (!routeMode.equals("POINTS") && !routeMode.equals("ROUND_TRIP")) {
            routeMode = response.targetDistanceKm() != null && destination == null
                    ? "ROUND_TRIP" : "POINTS";
        }
        BigDecimal targetDistanceKm = response.targetDistanceKm();
        boolean roundTripValid = routeMode.equals("ROUND_TRIP") && targetDistanceKm != null
                && targetDistanceKm.compareTo(new BigDecimal("0.5")) >= 0
                && targetDistanceKm.compareTo(new BigDecimal("50")) <= 0;
        boolean pointsValid = routeMode.equals("POINTS") && start != null && destination != null
                && !start.equalsIgnoreCase(destination);
        if (start == null || (!roundTripValid && !pointsValid)) {
            throw new BusinessException(ErrorCode.AI_ROUTE_INSUFFICIENT_CONTEXT);
        }
        List<String> waypoints = response.waypoints() == null ? List.of()
                : response.waypoints().stream().map(this::query).filter(Objects::nonNull)
                        .distinct().limit(MAX_WAYPOINTS).toList();
        return new NormalizedPlan(routeMode, activityType, start,
                routeMode.equals("POINTS") ? waypoints : List.of(),
                routeMode.equals("POINTS") ? destination : null,
                routeMode.equals("ROUND_TRIP") ? targetDistanceKm : null);
    }

    private String query(AiPlace place) {
        if (place == null || place.query() == null || place.query().isBlank()) return null;
        String value = place.query().trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private NearestRouteNodeResponse resolve(String query, RouteNodeRole role,
                                             RouteActivityType activityType) {
        KakaoKeywordResponse response;
        try {
            response = kakaoClient.get().uri(builder -> builder
                            .path("/v2/local/search/keyword.json")
                            .queryParam("query", query)
                            .queryParam("size", 1)
                            .build())
                    .retrieve().body(KakaoKeywordResponse.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_ROUTE_PLACE_NOT_FOUND);
        }
        if (response == null || response.documents() == null || response.documents().isEmpty()) {
            throw new BusinessException(ErrorCode.AI_ROUTE_PLACE_NOT_FOUND);
        }
        KakaoPlace place = response.documents().getFirst();
        try {
            return routeService.nearest(Double.parseDouble(place.x()),
                    Double.parseDouble(place.y()), role, activityType);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_ROUTE_PLACE_NOT_FOUND);
        }
    }

    private BigDecimal defaultSpeed(RouteActivityType activityType) {
        return switch (activityType) {
            case WALK -> new BigDecimal("4.0");
            case RUN -> new BigDecimal("8.0");
            case CYCLE -> new BigDecimal("15.0");
        };
    }

    private static RestClient buildAiClient(String baseUrl, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    record AiRouteRequest(
            @JsonProperty("room_id") String roomId,
            List<AiMessage> messages
    ) { }

    record AiMessage(
            @JsonProperty("sender_id") String senderId,
            String content,
            @JsonProperty("sent_at") String sentAt
    ) { }

    record AiRouteResponse(
            String status,
            @JsonProperty("route_mode") String routeMode,
            @JsonProperty("activity_type") String activityType,
            AiPlace start,
            List<AiPlace> waypoints,
            AiPlace destination,
            @JsonProperty("target_distance_km") BigDecimal targetDistanceKm,
            String message
    ) { }

    record AiPlace(String query) { }

    record KakaoKeywordResponse(List<KakaoPlace> documents) { }

    record KakaoPlace(String x, String y) { }

    record NormalizedPlan(String routeMode, RouteActivityType activityType, String start,
                          List<String> waypoints, String destination,
                          BigDecimal targetDistanceKm) { }
}
