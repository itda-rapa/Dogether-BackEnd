package itda.block.controller;

import itda.block.dto.BlockCreateRequest;
import itda.block.dto.response.BlockListResponse;
import itda.block.dto.response.BlockResponse;
import itda.block.service.BlockService;
import itda.block.service.BlockService.BlockResult;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/blocks")
@RequiredArgsConstructor
public class BlockController implements BlockSwaggerSupporter {

    private final BlockService blockService;

    @PostMapping
    public ResponseEntity<ApiResponse<BlockResponse>> block(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody BlockCreateRequest request
    ) {
        BlockResult result = blockService.block(currentUser.id(), request);
        if (result.created()) {
            return ResponseEntity.status(201).body(
                    ApiResponse.created(result.block(), "차단이 완료되었습니다.")
            );
        }
        return ResponseEntity.ok(
                ApiResponse.ok(result.block(), "이미 차단한 사용자입니다.")
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<BlockListResponse>> listBlocks(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        BlockListResponse body = blockService.listBlocks(currentUser.id(), cursor, limit);
        return ResponseEntity.ok(ApiResponse.ok(body, "차단 목록 조회 성공"));
    }
}
