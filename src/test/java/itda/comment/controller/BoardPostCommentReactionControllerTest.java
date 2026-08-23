package itda.comment.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.comment.domain.CommentReactionType;
import itda.comment.dto.CommentReactionResponse;
import itda.comment.dto.CommentRequestParser;
import itda.comment.service.BoardPostCommentService;
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

@WebMvcTest(BoardPostCommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CommentRequestParser.class)
class BoardPostCommentReactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BoardPostCommentService service;
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
    void helpfulPutAndDeleteExposeTheNoBodyReactionEnvelope() throws Exception {
        given(service.addReaction(1L, 101L, CommentReactionType.HELPFUL))
                .willReturn(new CommentReactionResponse(101L, CommentReactionType.HELPFUL, true, 2));
        given(service.removeReaction(1L, 101L, CommentReactionType.HELPFUL))
                .willReturn(new CommentReactionResponse(101L, CommentReactionType.HELPFUL, false, 1));

        mockMvc.perform(put("/comments/{commentId}/reactions/{type}", 101L, "HELPFUL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.commentId").value(101))
                .andExpect(jsonPath("$.data.type").value("HELPFUL"))
                .andExpect(jsonPath("$.data.reacted").value(true))
                .andExpect(jsonPath("$.data.reactionCount").value(2));
        mockMvc.perform(delete("/comments/{commentId}/reactions/{type}", 101L, "HELPFUL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reacted").value(false))
                .andExpect(jsonPath("$.data.reactionCount").value(1));
    }

    @Test
    void unsupportedTypeIsValidationFailedBeforeTheServiceIsInvoked() throws Exception {
        mockMvc.perform(put("/comments/{commentId}/reactions/{type}", 101L, "LIKE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        then(service).shouldHaveNoInteractions();
    }

    @Test
    void activePetSelfAndHiddenTargetErrorsPreserveTheReactionContract() throws Exception {
        given(service.addReaction(1L, 101L, CommentReactionType.HELPFUL))
                .willThrow(new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED));
        given(service.removeReaction(1L, 102L, CommentReactionType.HELPFUL))
                .willThrow(new BusinessException(ErrorCode.BOARD_POST_COMMENT_SELF_REACTION_FORBIDDEN));
        given(service.addReaction(1L, 103L, CommentReactionType.HELPFUL))
                .willThrow(new BusinessException(ErrorCode.BOARD_POST_COMMENT_NOT_FOUND));

        mockMvc.perform(put("/comments/{commentId}/reactions/{type}", 101L, "HELPFUL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(delete("/comments/{commentId}/reactions/{type}", 102L, "HELPFUL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_SELF_REACTION_FORBIDDEN"));
        mockMvc.perform(put("/comments/{commentId}/reactions/{type}", 103L, "HELPFUL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_NOT_FOUND"));
    }
}
