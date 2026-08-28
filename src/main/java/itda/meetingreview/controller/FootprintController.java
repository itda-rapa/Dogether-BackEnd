package itda.meetingreview.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingreview.dto.FootprintListResponse;
import itda.meetingreview.service.FootprintQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/footprints")
@RequiredArgsConstructor
public class FootprintController implements FootprintSwaggerSupporter {

    private final FootprintQueryService footprintQueryService;

    /** 내 Active Pet 발자국 목록. (createdAt DESC, id DESC) 커서 페이지, size 기본 20·최대 100. */
    @GetMapping
    public ResponseEntity<ApiResponse<FootprintListResponse>> listMine(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        FootprintListResponse response = footprintQueryService.listMine(currentUser.id(), cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response, "발자국 조회 성공"));
    }
}
