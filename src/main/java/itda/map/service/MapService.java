package itda.map.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.map.dto.KakaoCoordToRegionResponse;
import itda.map.dto.NeighborhoodRequest;
import itda.neighborhood.domain.Neighborhood;
import itda.neighborhood.repository.NeighborhoodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@Transactional
@RequiredArgsConstructor
public class MapService {

    private final RestClient restClient;
    private final NeighborhoodRepository neighborhoodRepository;

    public Neighborhood getNeighborhood(NeighborhoodRequest request) {

        KakaoCoordToRegionResponse response = restClient.get()
                .uri(
                        uriBuilder ->
                                uriBuilder.path("/v2/local/geo/coord2regioncode.json")
                                        .queryParam("x", request.longitude())
                                        .queryParam("y", request.latitude())
                                        .build()
                ).retrieve()
                .body(KakaoCoordToRegionResponse.class);

        if (response == null)
            throw new BusinessException(ErrorCode.KAKAO_REGION_NO_RESPONSE);

        KakaoCoordToRegionResponse.Document document =
                response.documents().stream()
                        .filter(region -> "H".equals(region.regionType()))
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException("행정동을 찾을 수 없습니다.")
                        );

        return neighborhoodRepository.findByCodeOrThrow(
                document.code()
        );
    }
}
