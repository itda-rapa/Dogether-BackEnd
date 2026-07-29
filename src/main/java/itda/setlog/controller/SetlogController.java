package itda.setlog.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.greeting.dto.GreetingResponse;
import itda.greeting.service.GreetingService;
import itda.setlog.domain.ReactionType;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.service.SetlogQueryService;
import itda.setlog.service.SetlogReactionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/setlogs")
@RequiredArgsConstructor
public class SetlogController {

    private final SetlogQueryService setlogQueryService;
    private final SetlogReactionService setlogReactionService;
    private final GreetingService greetingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SetlogResponse>>> getSetlogs(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        List<SetlogResponse> setlogs =
                setlogQueryService.getSeedSetlogs(currentUser.id());
        return ResponseEntity.ok(
                ApiResponse.ok(setlogs, "시드 셋로그 조회 성공")
        );
    }

    @PutMapping("/{setlogId}/reactions/{type}")
    public ResponseEntity<ApiResponse<SetlogReactionResponse>> addReaction(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId,
            @PathVariable ReactionType type
    ) {
        SetlogReactionResponse reaction =
                setlogReactionService.addReaction(
                        currentUser.id(),
                        setlogId,
                        type
                );
        return ResponseEntity.ok(
                ApiResponse.ok(reaction, "셋로그 반응 추가 성공")
        );
    }

    @DeleteMapping("/{setlogId}/reactions/{type}")
    public ResponseEntity<ApiResponse<SetlogReactionResponse>> removeReaction(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId,
            @PathVariable ReactionType type
    ) {
        SetlogReactionResponse reaction =
                setlogReactionService.removeReaction(
                        currentUser.id(),
                        setlogId,
                        type
                );
        return ResponseEntity.ok(
                ApiResponse.ok(reaction, "셋로그 반응 취소 성공")
        );
    }

    @PostMapping("/{setlogId}/greetings")
    public ResponseEntity<ApiResponse<GreetingResponse>> sendGreeting(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long setlogId
    ) {
        GreetingResponse greeting = greetingService.send(
                currentUser.id(),
                setlogId
        );
        return ResponseEntity.status(201).body(
                ApiResponse.created(greeting, "인사 전송 및 채팅방 생성 성공")
        );
    }
}
