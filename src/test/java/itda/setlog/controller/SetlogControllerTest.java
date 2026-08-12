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
import itda.common.exception.BusinessException;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.friend.domain.FriendRelationship;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.dto.GreetingResponse;
import itda.greeting.service.GreetingService;
import itda.setlog.domain.ReactionType;
import itda.setlog.dto.SetlogAuthorPetResponse;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.service.SetlogReadService;
import itda.setlog.service.SetlogReactionService;
import itda.setlog.service.SetlogUploadSessionService;
import itda.setlog.service.SetlogUploadCompletionService;
import itda.setlog.dto.SetlogUploadCompleteRequest;
import itda.setlog.dto.SetlogUploadCreateRequest;
import itda.setlog.dto.SetlogUploadCreateResponse;
import java.util.Map;
import java.util.UUID;
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
    private SetlogUploadSessionService setlogUploadSessionService;

    @MockitoBean
    private SetlogUploadCompletionService setlogUploadCompletionService;

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
    @DisplayName("POST /setlogs/uploads는 Presigned PUT 세션을 201로 반환한다")
    void createUploadSessionReturnsCreated() throws Exception {
        UUID uploadId = UUID.fromString("10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35");
        Instant expiresAt = Instant.parse("2026-08-12T01:15:00Z");
        SetlogUploadCreateRequest request = new SetlogUploadCreateRequest(
                12L, "walk.mp4", "video/mp4", 12582912L
        );
        given(setlogUploadSessionService.create(USER_ID, request)).willReturn(
                new SetlogUploadCreateResponse(
                        uploadId,
                        "https://storage.example/upload",
                        "setlogs/1/12/10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35.mp4",
                        Map.of("Content-Type", "video/mp4"),
                        expiresAt
                )
        );

        mockMvc.perform(post("/setlogs/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "petId": 12,
                                  "fileName": "walk.mp4",
                                  "contentType": "video/mp4",
                                  "size": 12582912
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://storage.example/upload"))
                .andExpect(jsonPath("$.data.headers.Content-Type").value("video/mp4"))
                .andExpect(jsonPath("$.data.expiresAt").value(expiresAt.toString()));

        then(setlogUploadSessionService).should().create(USER_ID, request);
    }

    @Test
    @DisplayName("POST complete 최초 요청은 USER 셋로그를 201로 반환한다")
    void completeUploadReturnsCreated() throws Exception {
        UUID uploadId = UUID.fromString("10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35");
        UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        given(setlogUploadCompletionService.complete(USER_ID, uploadId, requestId))
                .willReturn(new SetlogUploadCompletionService.CompletionResult(
                        completedResponse(), false));

        mockMvc.perform(post("/setlogs/uploads/{uploadId}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"550e8400-e29b-41d4-a716-446655440000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.setlogId").value(SETLOG_ID))
                .andExpect(jsonPath("$.data.source").value("USER"));

        then(setlogUploadCompletionService).should().complete(USER_ID, uploadId, requestId);
    }

    @Test
    @DisplayName("POST complete 동일 멱등 요청은 기존 셋로그를 200으로 반환한다")
    void replayCompleteUploadReturnsOk() throws Exception {
        UUID uploadId = UUID.fromString("10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35");
        UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        given(setlogUploadCompletionService.complete(USER_ID, uploadId, requestId))
                .willReturn(new SetlogUploadCompletionService.CompletionResult(
                        completedResponse(), true));

        mockMvc.perform(post("/setlogs/uploads/{uploadId}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"550e8400-e29b-41d4-a716-446655440000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.setlogId").value(SETLOG_ID));
    }

    @Test
    @DisplayName("POST complete는 clientRequestId가 없으면 400을 반환한다")
    void completeUploadRequiresClientRequestId() throws Exception {
        mockMvc.perform(post("/setlogs/uploads/{uploadId}/complete", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        then(setlogUploadCompletionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("기존 POST /setlogs HEAD 우회 경로는 제거된다")
    void directSetlogCreationRouteIsRemoved() throws Exception {
        mockMvc.perform(post("/setlogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaId\":30}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /setlogs/uploads는 필수 메타데이터가 없으면 400을 반환한다")
    void createUploadSessionRejectsMissingMetadata() throws Exception {
        mockMvc.perform(post("/setlogs/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petId":12,"fileName":"walk.mp4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        then(setlogUploadSessionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("POST /setlogs/uploads는 크기 초과를 413으로 반환한다")
    void createUploadSessionReturnsPayloadTooLarge() throws Exception {
        SetlogUploadCreateRequest request = new SetlogUploadCreateRequest(
                12L, "walk.mp4", "video/mp4", 209715201L
        );
        given(setlogUploadSessionService.create(USER_ID, request))
                .willThrow(new BusinessException(ErrorCode.UPLOAD_SIZE_EXCEEDED));

        mockMvc.perform(post("/setlogs/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petId":12,"fileName":"walk.mp4","contentType":"video/mp4","size":209715201}
                                """))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.UPLOAD_SIZE_EXCEEDED.name()));
    }

    @Test
    @DisplayName("POST /setlogs/uploads는 지원하지 않는 타입을 415로 반환한다")
    void createUploadSessionReturnsUnsupportedMediaType() throws Exception {
        SetlogUploadCreateRequest request = new SetlogUploadCreateRequest(
                12L, "walk.mov", "video/quicktime", 1024L
        );
        given(setlogUploadSessionService.create(USER_ID, request))
                .willThrow(new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_UNSUPPORTED));

        mockMvc.perform(post("/setlogs/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petId":12,"fileName":"walk.mov","contentType":"video/quicktime","size":1024}
                                """))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code")
                        .value(ErrorCode.UPLOAD_CONTENT_TYPE_UNSUPPORTED.name()));
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
    @DisplayName("GET /setlogs는 cursor와 size 생략을 허용한다")
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

    private SetlogResponse completedResponse() {
        return new SetlogResponse(
                SETLOG_ID,
                itda.setlog.dto.SetlogSource.USER,
                new SetlogAuthorPetResponse(
                        20L, "몽이#A7K2", "몽이", null, false,
                        FriendRelationship.NONE
                ),
                "https://example.com/user.mp4",
                Instant.parse("2026-08-12T01:10:00Z"),
                null,
                0,
                0,
                List.of(),
                false,
                Instant.parse("2026-08-12T01:00:00Z")
        );
    }
}
