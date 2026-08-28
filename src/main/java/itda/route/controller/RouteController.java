package itda.route.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.route.dto.NearestRouteNodeResponse;
import itda.route.dto.RouteAcceptedResponse;
import itda.route.dto.RouteCreateRequest;
import itda.route.dto.RouteHeatmapResponse;
import itda.route.dto.RouteResponse;
import itda.route.dto.RoundTripRouteCreateRequest;
import itda.route.domain.RouteActivityType;
import itda.route.domain.RouteNodeRole;
import itda.route.service.RouteService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/nodes/nearest")
    public ResponseEntity<ApiResponse<NearestRouteNodeResponse>> nearest(
            @RequestParam double longitude, @RequestParam double latitude,
            @RequestParam RouteNodeRole role,
            @RequestParam RouteActivityType activityType) {
        return ResponseEntity.ok(ApiResponse.ok(routeService.nearest(
                        longitude, latitude, role, activityType),
                "가장 가까운 경로 노드를 찾았습니다."));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RouteAcceptedResponse>> create(
            @AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody RouteCreateRequest request) {
        RouteAcceptedResponse response = routeService.create(user.id(), request);
        return ResponseEntity.accepted().location(URI.create("/routes/" + response.requestId()))
                .body(ApiResponse.ok(response, "경로 계산을 시작했습니다."));
    }

    @PostMapping("/round-trips")
    public ResponseEntity<ApiResponse<RouteAcceptedResponse>> createRoundTrip(
            @AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody RoundTripRouteCreateRequest request) {
        RouteAcceptedResponse response = routeService.createRoundTrip(user.id(), request);
        return ResponseEntity.accepted().location(URI.create("/routes/" + response.requestId()))
                .body(ApiResponse.ok(response, "왕복 경로 계산을 시작했습니다."));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<RouteResponse>> get(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID requestId) {
        return ResponseEntity.ok(ApiResponse.ok(routeService.get(user.id(), requestId),
                "경로를 조회했습니다."));
    }

    @PostMapping("/{requestId}/save")
    public ResponseEntity<ApiResponse<RouteResponse>> save(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID requestId) {
        return ResponseEntity.ok(ApiResponse.ok(routeService.save(user.id(), requestId),
                "경로를 저장했습니다."));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RouteResponse>>> list(
            @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(ApiResponse.ok(routeService.list(user.id()),
                "저장된 경로 목록을 조회했습니다."));
    }

    @GetMapping("/heatmap")
    public ResponseEntity<ApiResponse<RouteHeatmapResponse>> heatmap() {
        return ResponseEntity.ok(ApiResponse.ok(routeService.heatmap(),
                "저장 경로 이용 빈도를 조회했습니다."));
    }
}
