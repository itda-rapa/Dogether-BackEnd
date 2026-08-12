package itda.boardpost.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.dto.BoardPostReactionResponse;
import itda.boardpost.dto.BoardPostRequestParser;
import itda.boardpost.service.BoardPostService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BoardPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BoardPostRequestParser.class)
class BoardPostReactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BoardPostService service;
    @MockitoBean private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(1L, "user@example.test", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void putAndDeleteExposeTheIdempotentMutationEnvelopeWithoutRequestBody() throws Exception {
        given(service.addReaction(1L, 101L, BoardPostReactionType.LIKE))
                .willReturn(new BoardPostReactionResponse(101L, BoardPostReactionType.LIKE, true, 4));
        given(service.removeReaction(1L, 101L, BoardPostReactionType.LIKE))
                .willReturn(new BoardPostReactionResponse(101L, BoardPostReactionType.LIKE, false, 3));

        mockMvc.perform(put("/posts/{postId}/reactions/{type}", 101L, "LIKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("게시글 반응 상태가 변경되었습니다."))
                .andExpect(jsonPath("$.data.postId").value(101))
                .andExpect(jsonPath("$.data.type").value("LIKE"))
                .andExpect(jsonPath("$.data.reacted").value(true))
                .andExpect(jsonPath("$.data.reactionCount").value(4))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(delete("/posts/{postId}/reactions/{type}", 101L, "LIKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reacted").value(false))
                .andExpect(jsonPath("$.data.reactionCount").value(3));
    }

    @Test
    void unsupportedReactionTypeIsValidationFailedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(put("/posts/{postId}/reactions/{type}", 101L, "CUTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void activePetAndHiddenPostErrorsUseTheIssueStatusesAndCodes() throws Exception {
        given(service.addReaction(1L, 101L, BoardPostReactionType.LIKE))
                .willThrow(new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED));
        given(service.removeReaction(1L, 102L, BoardPostReactionType.LIKE))
                .willThrow(new BusinessException(ErrorCode.BOARD_POST_NOT_FOUND));

        mockMvc.perform(put("/posts/{postId}/reactions/{type}", 101L, "LIKE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(delete("/posts/{postId}/reactions/{type}", 102L, "LIKE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
    }
}
