package itda.map.controller;

import itda.common.dto.ApiResponse;
import itda.map.dto.CulturalFacilityResponse;
import itda.map.dto.NearbyCulturalFacilityRequest;
import itda.map.service.CulturalFacilityService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/map/cultural-facilities")
public class CulturalFacilityController {

    private final CulturalFacilityService service;

    public CulturalFacilityController(CulturalFacilityService service) {
        this.service = service;
    }

    @PostMapping("/nearby")
    public ResponseEntity<ApiResponse<List<CulturalFacilityResponse>>> findNearest(
            @RequestBody NearbyCulturalFacilityRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.findNearest(request.category(), request.longitude(), request.latitude()),
                "가장 가까운 문화·반려동물 시설이 조회되었습니다."));
    }
}
