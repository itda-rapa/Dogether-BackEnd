package itda.neighborhood.controller;

import itda.common.dto.ApiResponse;
import itda.neighborhood.dto.NeighborhoodResponse;
import itda.neighborhood.service.NeighborhoodService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/neighborhoods")
public class NeighborhoodController {

    private final NeighborhoodService neighborhoodService;

    public NeighborhoodController(NeighborhoodService neighborhoodService) {
        this.neighborhoodService = neighborhoodService;
    }

    @GetMapping
    public ApiResponse<List<NeighborhoodResponse>> listNeighborhoods() {
        return ApiResponse.ok(
                neighborhoodService.listActiveNeighborhoods(),
                "가입 가능한 동네 목록을 조회했습니다."
        );
    }
}
