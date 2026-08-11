package itda.setlog.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.friend.domain.FriendRelationship;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.dto.GreetingResponse;
import itda.greeting.service.GreetingService;
import itda.setlog.domain.ReactionType;
import itda.setlog.dto.SetlogAuthorPetResponse;
import itda.setlog.dto.SetlogCreateRequest;
import itda.setlog.dto.SetlogCreateResponse;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.service.SetlogCreationService;
import itda.setlog.service.SetlogReadService;
import itda.setlog.service.SetlogReactionService;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SetlogController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SetlogController")
class SetlogControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long SETLOG_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SetlogReadService setlogReadService;

    @MockitoBean
    private SetlogReactionService setlogReactionService;

    @MockitoBean
    private SetlogCreationService setlogCreationService;

    @MockitoBean
    private GreetingService greetingService;

    @MockitoBean
    private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        CurrentUser currentUser = new CurrentUser(
                USER_ID,
                "user@example.com",
                Role.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        currentUser,
                        null,
                        currentUser.getAuthorities()
                )
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /setlogs는 업로드된 영상으로 셋로그를 생성한다")
    void createSetlogReturnsCreated() throws Exception {
        Instant createdAt = Instant.parse("2026-07-30T01:00:00Z");
        given(setlogCreationService.create(
                USER_ID,
                new SetlogCreateRequest(30L, "같이 놀아요")
        )).willReturn(new SetlogCreateResponse(
                SETLOG_ID,
                20L,
                30L,
                "같이 놀아요",
                SetlogStatus.VISIBLE,
                createdAt
        ));

        mockMvc.perform(post("/setlogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": 30,
                                  "caption": "같이 놀아요"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.setlogId")
                        .value(SETLOG_ID))
                .andExpect(jsonPath("$.data.authorPetId").value(20L))
                .andExpect(jsonPath("$.data.mediaId").value(30L))
                .andExpect(jsonPath("$.data.status")
                        .value("VISIBLE"));

        then(setlogCreationService).should().create(
                USER_ID,
                new SetlogCreateRequest(30L, "같이 놀아요")
        );
    }

    @Test
    @DisplayName("POST /setlogs는 mediaId가 없으면 400을 반환한다")
    void createSetlogRejectsMissingMediaId() throws Exception {
        mockMvc.perform(post("/setlogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caption": "같이 놀아요"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value(ErrorCode.VALIDATION_FAILED.name()));

        then(setlogCreationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("GET /setlogs는 cursor 페이지 응답을 반환한다")
    void getSetlogsReturnsCursorPage() throws Exception {
        given(setlogReadService.getSetlogs(USER_ID, "next-from-client", 5))
                .willReturn(new SetlogListResponse(
                        List.of(setlogResponse()),
                        "next-from-server",
                        true
                ));

        mockMvc.perform(get("/setlogs")
                        .param("cursor", "next-from-client")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].setlogId").value(SETLOG_ID))
                .andExpect(jsonPath("$.data.items[0].source").value("SEED"))
                .andExpect(jsonPath("$.data.items[0].authorPet.petId").value(20L))
                .andExpect(jsonPath("$.data.items[0].mediaUrl")
                        .value("https://example.com/seed.mp4"))
                .andExpect(jsonPath("$.data.items[0].myReactions[0]")
                        .value("CUTE"))
                .andExpect(jsonPath("$.data.items[0].canInteract").value(true))
                .andExpect(jsonPath("$.data.nextCursor")
                        .value("next-from-server"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.page").doesNotExist());

        then(setlogReadService).should()
                .getSetlogs(USER_ID, "next-from-client", 5);
    }

    @Test
    @DisplayName("GET /setlogs는 cursor와 limit 생략을 허용한다")
    void getSetlogsAllowsOmittedPageParameters() throws Exception {
        given(setlogReadService.getSetlogs(USER_ID, null, null))
                .willReturn(new SetlogListResponse(
                        List.of(),
                        null,
                        false
                ));

        mockMvc.perform(get("/setlogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        then(setlogReadService).should().getSetlogs(USER_ID, null, null);
    }

    @Test
    @DisplayName("PUT 반응 API는 reacted=true와 최신 카운트를 반환한다")
    void addReactionReturnsReactedTrue() throws Exception {
        given(setlogReactionService.addReaction(
                USER_ID,
                SETLOG_ID,
                ReactionType.CUTE
        )).willReturn(new SetlogReactionResponse(
                SETLOG_ID,
                ReactionType.CUTE,
                true,
                2,
                1
        ));

        mockMvc.perform(put("/setlogs/10/reactions/CUTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setlogId").value(SETLOG_ID))
                .andExpect(jsonPath("$.data.type").value("CUTE"))
                .andExpect(jsonPath("$.data.reacted").value(true))
                .andExpect(jsonPath("$.data.cuteCount").value(2))
                .andExpect(jsonPath("$.data.likeCount").value(1));
    }

    @Test
    @DisplayName("DELETE 반응 API는 reacted=false와 최신 카운트를 반환한다")
    void removeReactionReturnsReactedFalse() throws Exception {
        given(setlogReactionService.removeReaction(
                USER_ID,
                SETLOG_ID,
                ReactionType.LIKE
        )).willReturn(new SetlogReactionResponse(
                SETLOG_ID,
                ReactionType.LIKE,
                false,
                2,
                0
        ));

        mockMvc.perform(delete("/setlogs/10/reactions/LIKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("LIKE"))
                .andExpect(jsonPath("$.data.reacted").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    @Test
    @DisplayName("지원하지 않는 반응 타입은 400을 반환한다")
    void invalidReactionTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/setlogs/10/reactions/GREETING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value(ErrorCode.VALIDATION_FAILED.name()));

        then(setlogReactionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("POST 인사 API는 DIRECT 방 정보와 함께 201을 반환한다")
    void sendGreetingReturnsCreated() throws Exception {
        given(greetingService.send(USER_ID, SETLOG_ID))
                .willReturn(new GreetingResponse(
                        30L,
                        40L,
                        GreetingStatus.SENT,
                        GreetingService.FIXED_MESSAGE,
                        Instant.parse("2026-07-31T01:00:00Z"),
                        Instant.parse("2026-07-30T01:00:00Z")
                ));

        mockMvc.perform(post("/setlogs/10/greetings"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.greetingId").value(30L))
                .andExpect(jsonPath("$.data.roomId").value(40L))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.fixedMessage")
                        .value("안녕하세요! 같이 놀아요."));

        then(greetingService).should().send(USER_ID, SETLOG_ID);
    }

    private SetlogResponse setlogResponse() {
        return new SetlogResponse(
                SETLOG_ID,
                new SetlogAuthorPetResponse(
                        20L,
                        "몽이#A7K2",
                        "몽이",
                        null,
                        true,
                        FriendRelationship.NONE
                ),
                "https://example.com/seed.mp4",
                Instant.parse("2026-07-30T01:10:00Z"),
                "같이 놀아요",
                2,
                1,
                List.of(ReactionType.CUTE),
                true,
                Instant.parse("2026-07-30T01:00:00Z")
        );
    }
}
