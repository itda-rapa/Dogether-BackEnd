package itda.chat.service;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
import itda.chat.dto.request.ShareChatMapLocationRequest;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.MapFacilitySnapshot;
import itda.chat.repository.ChatMessageRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChatMapDistanceService {

    private static final Duration LOCATION_TTL = Duration.ofMinutes(10);
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final ActivePetQueryService activePetQueryService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final ChatQueryService chatQueryService;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageResponseAssembler responseAssembler;
    private final ChatMessageEventPublisher messageEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatMapDistanceService(
            ActivePetQueryService activePetQueryService,
            PetDisplayQueryService petDisplayQueryService,
            ChatQueryService chatQueryService,
            ChatMessageRepository messageRepository,
            ChatMessageResponseAssembler responseAssembler,
            ChatMessageEventPublisher messageEventPublisher,
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.activePetQueryService = activePetQueryService;
        this.petDisplayQueryService = petDisplayQueryService;
        this.chatQueryService = chatQueryService;
        this.messageRepository = messageRepository;
        this.responseAssembler = responseAssembler;
        this.messageEventPublisher = messageEventPublisher;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void rememberLocation(
            long mapMessageId,
            long petId,
            BigDecimal longitude,
            BigDecimal latitude
    ) {
        String key = locationKey(mapMessageId);
        redisTemplate.opsForHash().put(key, Long.toString(petId), encode(longitude, latitude));
        redisTemplate.expire(key, LOCATION_TTL);
    }

    @Transactional
    public synchronized ChatMessageResponse shareLocation(
            long userId,
            long roomId,
            long mapMessageId,
            ShareChatMapLocationRequest request
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        chatQueryService.requireParticipant(roomId, actor.petId());
        ChatMessage message = messageRepository.findById(mapMessageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!message.getRoom().getId().equals(roomId) || message.getType() != MessageType.MAP) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        rememberLocation(mapMessageId, actor.petId(), request.longitude(), request.latitude());
        List<Coordinate> coordinates = readLocations(mapMessageId);
        List<MapFacilitySnapshot> facilities = readFacilities(message);
        List<MapFacilitySnapshot> ranked = rankFacilities(facilities, coordinates);
        try {
            message.updateMapFacilitiesJson(objectMapper.writeValueAsString(ranked));
            messageRepository.saveAndFlush(message);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        String senderNickname = petDisplayQueryService
                .getPetDisplaySummary(message.getSenderPetId()).nickname();
        ChatMessageResponse response = responseAssembler.toResponse(message, senderNickname);
        messageEventPublisher.publishAfterCommit(response);
        return response;
    }

    static List<MapFacilitySnapshot> rankFacilities(
            List<MapFacilitySnapshot> facilities,
            List<Coordinate> coordinates
    ) {
        List<MapFacilitySnapshot> averaged = new ArrayList<>();
        for (MapFacilitySnapshot facility : facilities) {
            double average = coordinates.stream()
                    .mapToDouble(location -> straightLineMeters(
                            location.latitude().doubleValue(), location.longitude().doubleValue(),
                            facility.latitude().doubleValue(), facility.longitude().doubleValue()))
                    .average()
                    .orElse(Double.NaN);
            averaged.add(new MapFacilitySnapshot(
                    facility.facilityId(), facility.name(), facility.address(),
                    facility.telephone(), facility.operatingHours(),
                    facility.longitude(), facility.latitude(), facility.distanceMeters(),
                    Double.isNaN(average) ? null : average,
                    coordinates.size(), null));
        }
        averaged.sort(Comparator
                .comparing(MapFacilitySnapshot::averageDistanceMeters,
                        Comparator.nullsLast(Double::compareTo))
                .thenComparing(MapFacilitySnapshot::facilityId));
        List<MapFacilitySnapshot> ranked = new ArrayList<>();
        for (int index = 0; index < averaged.size(); index++) {
            MapFacilitySnapshot facility = averaged.get(index);
            ranked.add(new MapFacilitySnapshot(
                    facility.facilityId(), facility.name(), facility.address(),
                    facility.telephone(), facility.operatingHours(),
                    facility.longitude(), facility.latitude(), facility.distanceMeters(),
                    facility.averageDistanceMeters(), facility.distanceParticipantCount(), index + 1));
        }
        return List.copyOf(ranked);
    }

    static double straightLineMeters(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude
    ) {
        double lat1 = Math.toRadians(fromLatitude);
        double lat2 = Math.toRadians(toLatitude);
        double deltaLat = Math.toRadians(toLatitude - fromLatitude);
        double deltaLon = Math.toRadians(toLongitude - fromLongitude);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<Coordinate> readLocations(long mapMessageId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(locationKey(mapMessageId));
        return entries.values().stream().map(value -> decode(value.toString())).toList();
    }

    private List<MapFacilitySnapshot> readFacilities(ChatMessage message) {
        try {
            return objectMapper.readValue(message.getMapFacilitiesJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String locationKey(long mapMessageId) {
        return "chat:map:locations:" + mapMessageId;
    }

    private String encode(BigDecimal longitude, BigDecimal latitude) {
        return longitude.toPlainString() + "," + latitude.toPlainString();
    }

    private Coordinate decode(String value) {
        String[] parts = value.split(",", 2);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return new Coordinate(new BigDecimal(parts[0]), new BigDecimal(parts[1]));
    }

    record Coordinate(BigDecimal longitude, BigDecimal latitude) {
    }
}
