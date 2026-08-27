package itda.map.controller;

import itda.common.dto.ApiResponse;
import itda.map.dto.MapPlaceResponse;
import itda.map.dto.MapPlaceType;
import itda.map.dto.NearbyMapPlaceRequest;
import itda.map.service.MapPlaceService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/map/places")
public class MapPlaceController {

    private final MapPlaceService mapPlaceService;

    public MapPlaceController(MapPlaceService mapPlaceService) {
        this.mapPlaceService = mapPlaceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MapPlaceResponse>>> getPlaces(
            @RequestParam MapPlaceType type,
            @RequestParam BigDecimal minLongitude,
            @RequestParam BigDecimal minLatitude,
            @RequestParam BigDecimal maxLongitude,
            @RequestParam BigDecimal maxLatitude
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                mapPlaceService.getPlaces(
                        type,
                        minLongitude,
                        minLatitude,
                        maxLongitude,
                        maxLatitude
                ),
                "지도 장소 목록이 조회되었습니다."
        ));
    }

    @PostMapping("/nearby")
    public ResponseEntity<ApiResponse<List<MapPlaceResponse>>> getNearbyPlaces(
            @RequestBody NearbyMapPlaceRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                mapPlaceService.getNearbyPlaces(
                        request.type(),
                        request.longitude(),
                        request.latitude(),
                        request.radiusMeters()
                ),
                "반경 내 지도 장소 목록이 조회되었습니다."
        ));
    }
}
