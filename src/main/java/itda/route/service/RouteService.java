package itda.route.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.node.repository.NodeRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.route.dto.NearestRouteNodeResponse;
import itda.route.dto.RouteAcceptedResponse;
import itda.route.dto.RouteCreateRequest;
import itda.route.dto.RouteHeatmapResponse;
import itda.route.dto.RouteResponse;
import itda.route.dto.RoundTripRouteCreateRequest;
import itda.route.domain.RouteActivityType;
import itda.route.domain.RouteNodeRole;
import itda.route.repository.RouteRequestRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteService {

    private static final BigDecimal DEFAULT_OWNER_WEIGHT_KG = new BigDecimal("70.00");

    private final NodeRepository nodeRepository;
    private final RouteRequestRepository routeRepository;
    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final ActivePetQueryService activePetQueryService;
    private final RouteRequestPublisher publisher;
    private final RouteEnvironmentService environmentService;

    public RouteService(NodeRepository nodeRepository, RouteRequestRepository routeRepository,
                        UserRepository userRepository, PetRepository petRepository,
                        ActivePetQueryService activePetQueryService, RouteRequestPublisher publisher,
                        RouteEnvironmentService environmentService) {
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.userRepository = userRepository;
        this.petRepository = petRepository;
        this.activePetQueryService = activePetQueryService;
        this.publisher = publisher;
        this.environmentService = environmentService;
    }

    @Transactional(readOnly = true)
    public NearestRouteNodeResponse nearest(double longitude, double latitude,
                                             RouteNodeRole role, RouteActivityType activityType) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new BusinessException(ErrorCode.LOCATION_INVALID);
        }
        NodeRepository.NearestNodeProjection node =
                nodeRepository.findNearestRouteNodeOrThrow(
                        longitude, latitude, role.name(), activityType.name());
        if (node.getNodeId() == null) throw new BusinessException(ErrorCode.NODE_NOT_FOUND);
        return new NearestRouteNodeResponse(
                node.getNodeId(),
                BigDecimal.valueOf(node.getLongitude()),
                BigDecimal.valueOf(node.getLatitude()));
    }

    public RouteAcceptedResponse create(long userId, RouteCreateRequest request) {
        validateSpeed(request.activityType(), request.speedKmh());
        List<Long> waypoints = request.waypointNodeIds() == null ? List.of() : request.waypointNodeIds();
        List<Long> nodes = new ArrayList<>();
        nodes.add(request.startNodeId());
        nodes.addAll(waypoints);
        nodes.add(request.destinationNodeId());
        if (nodes.stream().distinct().count() != nodes.size() || !routeRepository.allNodesExist(nodes)) {
            throw new BusinessException(ErrorCode.ROUTE_NODES_INVALID);
        }

        ActivePetContext activePet = activePetQueryService.requireActivePet(userId);
        User user = userRepository.findByIdOrThrow(userId);
        Pet pet = petRepository.findById(activePet.petId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PET_NOT_FOUND));
        BigDecimal ownerWeight = user.getWeightKg() == null
                ? DEFAULT_OWNER_WEIGHT_KG : user.getWeightKg();
        BigDecimal petWeight = pet.getWeightKg();
        BigDecimal petCoefficient = petWeight == null ? null
                : petWeight.compareTo(BigDecimal.TEN) >= 0
                ? new BigDecimal("1.0") : new BigDecimal("1.9");

        UUID requestId = UUID.randomUUID();
        routeRepository.create(requestId, userId, pet.getId(), request.activityType().name(),
                request.priorityType().name(), request.speedKmh(), request.startNodeId(),
                waypoints, request.destinationNodeId(), ownerWeight,
                petWeight, petCoefficient, request.departureAt());
        try {
            publisher.publish(requestId);
        } catch (RuntimeException exception) {
            routeRepository.markFailed(requestId, "ROUTE_REQUEST_PUBLISH_FAILED");
            throw new BusinessException(ErrorCode.ROUTE_REQUEST_FAILED);
        }
        return new RouteAcceptedResponse(requestId, "QUEUED");
    }

    public RouteAcceptedResponse createRoundTrip(long userId, RoundTripRouteCreateRequest request) {
        validateSpeed(request.activityType(), request.speedKmh());
        if (!routeRepository.allNodesExist(List.of(request.startNodeId()))) {
            throw new BusinessException(ErrorCode.ROUTE_NODES_INVALID);
        }

        ActivePetContext activePet = activePetQueryService.requireActivePet(userId);
        User user = userRepository.findByIdOrThrow(userId);
        Pet pet = petRepository.findById(activePet.petId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PET_NOT_FOUND));
        BigDecimal ownerWeight = user.getWeightKg() == null
                ? DEFAULT_OWNER_WEIGHT_KG : user.getWeightKg();
        BigDecimal petWeight = pet.getWeightKg();
        BigDecimal petCoefficient = petWeight == null ? null
                : petWeight.compareTo(BigDecimal.TEN) >= 0
                ? new BigDecimal("1.0") : new BigDecimal("1.9");

        UUID requestId = UUID.randomUUID();
        routeRepository.createRoundTrip(requestId, userId, pet.getId(),
                request.activityType().name(), request.priorityType().name(), request.speedKmh(),
                request.startNodeId(), request.targetDistanceMeters(), ownerWeight,
                petWeight, petCoefficient, request.departureAt());
        try {
            publisher.publish(requestId);
        } catch (RuntimeException exception) {
            routeRepository.markFailed(requestId, "ROUTE_REQUEST_PUBLISH_FAILED");
            throw new BusinessException(ErrorCode.ROUTE_REQUEST_FAILED);
        }
        return new RouteAcceptedResponse(requestId, "QUEUED");
    }

    public RouteResponse get(long userId, UUID requestId) {
        RouteResponse response = routeRepository.findOwned(requestId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        if ("COMPLETED".equals(response.status()) && shouldRefreshEnvironment(response)) {
            routeRepository.updateEnvironment(requestId, environmentService.load(response));
            return routeRepository.findOwned(requestId, userId).orElse(response);
        }
        return response;
    }

    public RouteResponse save(long userId, UUID requestId) {
        if (!routeRepository.saveOwnedCompleted(requestId, userId)) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND);
        }
        return routeRepository.findOwned(requestId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> list(long userId) {
        return routeRepository.findAllOwned(userId);
    }

    @Transactional(readOnly = true)
    public RouteHeatmapResponse heatmap() {
        return RouteHeatmapResponse.from(routeRepository.findSavedRouteHeatmap());
    }

    private void validateSpeed(RouteActivityType activityType, BigDecimal speed) {
        boolean valid = switch (activityType) {
            case WALK -> between(speed, "2.0", "7.0");
            case RUN -> between(speed, "6.0", "20.0");
            case CYCLE -> between(speed, "8.0", "40.0");
        };
        if (!valid) throw new BusinessException(ErrorCode.ROUTE_SPEED_INVALID);
    }

    private boolean shouldRefreshEnvironment(RouteResponse response) {
        return response.environmentInfo() == null
                || !"AVAILABLE".equals(response.environmentInfo().path("status").asText());
    }

    private boolean between(BigDecimal value, String min, String max) {
        return value != null && value.compareTo(new BigDecimal(min)) >= 0
                && value.compareTo(new BigDecimal(max)) <= 0;
    }
}
