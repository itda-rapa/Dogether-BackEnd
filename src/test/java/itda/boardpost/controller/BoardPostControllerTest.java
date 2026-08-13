package itda.boardpost.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.boardpost.dto.BoardPostAuthorPetResponse;
import itda.boardpost.dto.BoardPostImageResponse;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.dto.BoardPostRequestParser;
import itda.boardpost.service.BoardPostService;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BoardPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BoardPostRequestParser.class)
class BoardPostControllerTest {

    private static final long USER_ID = 1L;
    private static final long BOARD_ID = 2L;
    private static final long POST_ID = 3L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BoardPostService service;
    @MockitoBean private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(USER_ID, "user@example.test", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturnsTheImageWireContract() throws Exception {
        given(service.create(eq(USER_ID), eq(BOARD_ID), any())).willReturn(response(List.of(
                new BoardPostImageResponse(10L, "https://example.test/media/10", 0)
        )));

        mockMvc.perform(post("/boards/{boardId}/posts", BOARD_ID)
                        .contentType("application/json")
                        .content("{\"title\":\"title\",\"content\":\"content\",\"mediaIds\":[10]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.images[0].mediaId").value(10))
                .andExpect(jsonPath("$.data.images[0].url").value("https://example.test/media/10"))
                .andExpect(jsonPath("$.data.images[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data.reactionCount").value(0))
                .andExpect(jsonPath("$.data.reactedByMe").value(false));
    }

    @Test
    void patchRetainsImagesAndDetailUsesAnEmptyImagesArray() throws Exception {
        BoardPostResponse attached = response(List.of(
                new BoardPostImageResponse(10L, "https://example.test/media/10", 0)
        ));
        given(service.update(eq(USER_ID), eq(POST_ID), any())).willReturn(attached);
        given(service.detail(USER_ID, POST_ID)).willReturn(response(List.of()));

        mockMvc.perform(patch("/posts/{postId}", POST_ID)
                        .contentType("application/json")
                        .content("{\"title\":\"changed\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images[0].mediaId").value(10))
                .andExpect(jsonPath("$.data.images[0].url").value("https://example.test/media/10"))
                .andExpect(jsonPath("$.data.images[0].displayOrder").value(0));
        mockMvc.perform(get("/posts/{postId}", POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images").isArray())
                .andExpect(jsonPath("$.data.images").isEmpty())
                .andExpect(jsonPath("$.data.reactionCount").value(0))
                .andExpect(jsonPath("$.data.reactedByMe").value(false));
    }

    @Test
    void optimisticLockFailureMapsToHttp409ConcurrentUpdateConflict() throws Exception {
        given(service.update(eq(USER_ID), eq(POST_ID), any())).willThrow(
                new ObjectOptimisticLockingFailureException("itda.pet.domain.Pet", 1L)
        );

        mockMvc.perform(patch("/posts/{postId}", POST_ID)
                        .contentType("application/json")
                        .content("{\"title\":\"changed\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE_CONFLICT"));
    }

    private BoardPostResponse response(List<BoardPostImageResponse> images) {
        return new BoardPostResponse(
                POST_ID,
                BOARD_ID,
                new BoardPostAuthorPetResponse(4L, "pet#A1B2", "pet", null, false),
                "title",
                "content",
                images,
                0,
                false,
                0,
                Instant.parse("2026-08-11T00:00:00Z"),
                Instant.parse("2026-08-11T00:00:00Z")
        );
    }
}
