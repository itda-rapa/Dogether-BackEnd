package itda.meetingreview.service;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingreview.domain.Footprint;
import itda.meetingreview.dto.FootprintListResponse;
import itda.meetingreview.repository.FootprintRepository;
import itda.meetingreview.support.FootprintCursorCodec;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 Active Pet 발자국 조회(04_M3_API_상세명세.md §11 GET /footprints).
 * (createdAt DESC, id DESC) 커서 페이지이며 size 기본 20·최대 100.
 *
 * <p>발자국은 Pet 단위 snapshot 이므로 Active Pet 이 바뀌어도 과거 Pet 의 발자국은 그대로 남고,
 * 현재 Active Pet 기준으로만 조회된다. 상대 Pet 닉네임은 조회 시점의 현재 값을 읽는다.
 *
 * <p>상대 Pet 표시는 Pet 표시 공개 계약인 {@link PetDisplayQueryService#getPetDisplaySummaries}
 * 를 통해서만 조립한다. PetRepository/Pet Entity 직접 접근은 하지 않는다.
 */
@Service
public class FootprintQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ActivePetQueryService activePetQueryService;
    private final FootprintRepository footprintRepository;
    private final PetDisplayQueryService petDisplayQueryService;

    public FootprintQueryService(ActivePetQueryService activePetQueryService,
                                 FootprintRepository footprintRepository,
                                 PetDisplayQueryService petDisplayQueryService) {
        this.activePetQueryService = activePetQueryService;
        this.footprintRepository = footprintRepository;
        this.petDisplayQueryService = petDisplayQueryService;
    }

    @Transactional(readOnly = true)
    public FootprintListResponse listMine(Long userId, String cursor, Integer rawSize) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        int size = validateSize(rawSize);
        FootprintCursorCodec.CursorPayload payload = FootprintCursorCodec.decode(cursor);

        List<Footprint> footprints = payload == null
                ? new ArrayList<>(footprintRepository.findReceivedFirstPage(
                        actor.petId(), PageRequest.of(0, size + 1)))
                : new ArrayList<>(footprintRepository.findReceivedAfter(
                        actor.petId(), payload.createdAt(), payload.footprintId(),
                        PageRequest.of(0, size + 1)));
        boolean hasNext = footprints.size() > size;
        if (hasNext) {
            footprints = new ArrayList<>(footprints.subList(0, size));
        }

        Map<Long, PetDisplaySummary> summaries = loadPetDisplaySummaries(footprints);
        List<FootprintListResponse.FootprintItem> items = footprints.stream()
                .map(footprint -> new FootprintListResponse.FootprintItem(
                        footprint.getId(),
                        footprint.getMeetingId(),
                        new FootprintListResponse.CounterpartPet(
                                footprint.getCounterpartPetId(),
                                nicknameOf(summaries.get(footprint.getCounterpartPetId()))),
                        footprint.getEarnedDate(),
                        footprint.getCreatedAt()))
                .toList();
        String nextCursor = hasNext && !items.isEmpty()
                ? FootprintCursorCodec.encode(
                        items.get(items.size() - 1).footprintId(),
                        items.get(items.size() - 1).createdAt())
                : null;

        return new FootprintListResponse(items, CursorPage.of(nextCursor, hasNext));
    }

    /** 상대 Pet 닉네임은 {@link PetDisplaySummary} 공개 필드로만 조립한다. */
    private Map<Long, PetDisplaySummary> loadPetDisplaySummaries(List<Footprint> footprints) {
        if (footprints.isEmpty()) {
            return Map.of();
        }
        Set<Long> counterpartPetIds = footprints.stream()
                .map(Footprint::getCounterpartPetId)
                .collect(Collectors.toSet());
        return petDisplayQueryService.getPetDisplaySummaries(counterpartPetIds);
    }

    private String nicknameOf(PetDisplaySummary summary) {
        return summary == null ? null : summary.nickname();
    }

    private int validateSize(Integer rawSize) {
        int size = rawSize == null ? DEFAULT_SIZE : rawSize;
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return size;
    }
}
