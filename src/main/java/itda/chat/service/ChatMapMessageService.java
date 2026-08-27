package itda.chat.service;

import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.request.CreateChatMapMessageRequest;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.MapFacilitySnapshot;
import itda.chat.repository.ChatMessageRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.map.domain.CulturalFacilityCategory;
import itda.map.service.CulturalFacilityService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ChatMapMessageService {

    private final ActivePetQueryService activePetQueryService;
    private final ChatQueryService chatQueryService;
    private final ChatMessageRepository messageRepository;
    private final CulturalFacilityService culturalFacilityService;
    private final ChatMessageService chatMessageService;
    private final ChatMessageResponseAssembler responseAssembler;
    private final ChatMessageEventPublisher messageEventPublisher;
    private final ChatMapDistanceService mapDistanceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatMessageResponse create(
            long userId,
            long roomId,
            CreateChatMapMessageRequest request
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        chatQueryService.requireParticipant(roomId, actor.petId());
        var trigger = messageRepository.findById(request.triggerMessageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!trigger.getRoom().getId().equals(roomId)
                || trigger.getSenderType() != SenderType.PET
                || trigger.getType() != MessageType.TEXT
                || !actor.petId().equals(trigger.getSenderPetId())
                || !containsCategoryKeyword(trigger.getBody(), request.category())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        var existing = messageRepository.findByRoomIdAndMapTriggerMessageId(
                roomId, request.triggerMessageId());
        if (existing.isPresent()) {
            return responseAssembler.toResponse(existing.get(), actor.nickname());
        }

        var nearest = culturalFacilityService.findNearest(
                        request.category(), request.longitude(), request.latitude())
                .stream().toList();
        List<MapFacilitySnapshot> facilities = java.util.stream.IntStream.range(0, nearest.size())
                .mapToObj(index -> {
                    var facility = nearest.get(index);
                    return new MapFacilitySnapshot(
                        facility.facilityId(), facility.name(), facility.address(),
                        facility.telephone(), facility.operatingHours(),
                        facility.longitude(), facility.latitude(),
                        facility.distanceMeters(), facility.distanceMeters(), 1, index + 1);
                })
                .toList();
        if (facilities.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            ChatMessageResult result = chatMessageService.postMap(
                    roomId, actor.petId(), request.triggerMessageId(),
                    request.category().name(), objectMapper.writeValueAsString(facilities));
            ChatMessageResponse response = responseAssembler.toResponse(
                    result.message(), actor.nickname());
            if (result.created()) {
                mapDistanceService.rememberLocation(
                        result.message().getId(), actor.petId(),
                        request.longitude(), request.latitude());
                messageEventPublisher.publishAfterCommit(response);
            }
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private boolean containsCategoryKeyword(String body, CulturalFacilityCategory category) {
        if (body == null || category == null) return false;
        String keyword = switch (category) {
            case HOSPITAL -> "동물병원";
            case PHARMACY -> "동물약국";
            case ART_CENTER -> "문예회관";
            case ART_GALLERY -> "미술관";
            case BEAUTY -> "미용";
            case MUSEUM -> "박물관";
            case SHOP -> "반려동물용품";
            case RESTAURANT -> "식당";
            case TOUR_SPOT -> "여행지";
            case OUTSOURCE -> "위탁관리";
            case CAFE -> "카페";
            case RENTAL_HOUSE -> "펜션";
            case HOTEL -> "호텔";
        };
        return body.contains(keyword);
    }
}
