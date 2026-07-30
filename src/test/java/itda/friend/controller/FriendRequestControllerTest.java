package itda.friend.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.filter.JwtFilter;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestPetResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestCommandResult;
import itda.friend.service.FriendRequestCommandResult.Outcome;
import itda.friend.service.FriendRequestCommandService;
import itda.user.domain.Role;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FriendRequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class FriendRequestControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_PET_ID = 20L;
    private static final CurrentUser CURRENT_USER =
            new CurrentUser(USER_ID, "user@example.com", Role.USER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendRequestCommandService commandService;

    @MockitoBean
    private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        CURRENT_USER,
                        null,
                        CURRENT_USER.getAuthorities()
                )
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCreatedForPendingRequest() throws Exception {
        given(commandService.create(USER_ID, TARGET_PET_ID))
                .willReturn(result(Outcome.CREATED, null));

        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPetId\":20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.respondedAt").isEmpty())
                .andExpect(jsonPath("$.data.directRoomId").isEmpty());
    }

    @Test
    void returnsOkForAutoAccept() throws Exception {
        given(commandService.create(USER_ID, TARGET_PET_ID))
                .willReturn(result(Outcome.AUTO_ACCEPTED, 99L));

        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPetId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.requesterPet.relationship")
                        .value("FRIEND"))
                .andExpect(jsonPath("$.data.targetPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.directRoomId").value(99));
    }

    @Test
    void rejectsMissingTargetPetIdBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));

        verifyNoInteractions(commandService);
    }

    @Test
    void returnsConflictForSameDirectionPending() throws Exception {
        given(commandService.create(USER_ID, TARGET_PET_ID))
                .willThrow(new BusinessException(
                        ErrorCode.FRIEND_REQUEST_ALREADY_PENDING
                ));

        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPetId\":20}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("FRIEND_REQUEST_ALREADY_PENDING"));
    }

    @Test
    void returnsConflictForExistingFriendship() throws Exception {
        given(commandService.create(USER_ID, TARGET_PET_ID))
                .willThrow(new BusinessException(
                        ErrorCode.FRIENDSHIP_ALREADY_EXISTS
                ));

        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPetId\":20}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("FRIENDSHIP_ALREADY_EXISTS"));
    }

    @Test
    void acceptsRequestWithOkResponse() throws Exception {
        given(commandService.accept(USER_ID, 1L))
                .willReturn(response(true, 99L));

        mockMvc.perform(post("/friend-requests/1/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.requesterPet.relationship")
                        .value("FRIEND"))
                .andExpect(jsonPath("$.data.targetPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.directRoomId").value(99));
    }

    @Test
    void rejectsRequestWithOkResponse() throws Exception {
        FriendRequestResponse rejected = new FriendRequestResponse(
                1L,
                pet(10L, FriendRelationship.NONE),
                pet(TARGET_PET_ID, FriendRelationship.NONE),
                FriendRequestStatus.REJECTED,
                Instant.parse("2026-07-30T00:00:00Z"),
                Instant.parse("2026-07-30T00:01:00Z"),
                Instant.parse("2026-08-06T00:00:00Z"),
                null
        );
        given(commandService.reject(USER_ID, 1L)).willReturn(rejected);

        mockMvc.perform(post("/friend-requests/1/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.requesterPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.targetPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.directRoomId").isEmpty());
    }

    @Test
    void cancelsRequestWithoutResponseBody() throws Exception {
        mockMvc.perform(delete("/friend-requests/1"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(commandService).cancel(USER_ID, 1L);
    }

    @Test
    void hidesUnknownRequestAsNotFound() throws Exception {
        given(commandService.accept(USER_ID, 404L))
                .willThrow(new BusinessException(
                        ErrorCode.FRIEND_REQUEST_NOT_FOUND
                ));

        mockMvc.perform(post("/friend-requests/404/accept"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("FRIEND_REQUEST_NOT_FOUND"));
    }

    @Test
    void returnsBadRequestForSameOwnerAccept() throws Exception {
        given(commandService.accept(USER_ID, 1L))
                .willThrow(new BusinessException(
                        ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN
                ));

        mockMvc.perform(post("/friend-requests/1/accept"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("SAME_OWNER_INTERACTION_FORBIDDEN"));
    }

    private FriendRequestCommandResult result(
            Outcome outcome,
            Long roomId
    ) {
        boolean accepted = outcome == Outcome.AUTO_ACCEPTED;
        return new FriendRequestCommandResult(
                response(accepted, roomId),
                outcome
        );
    }

    private FriendRequestResponse response(boolean accepted, Long roomId) {
        Instant requestedAt = Instant.parse("2026-07-30T00:00:00Z");
        return new FriendRequestResponse(
                1L,
                pet(
                        10L,
                        accepted
                                ? FriendRelationship.FRIEND
                                : FriendRelationship.NONE
                ),
                pet(
                        TARGET_PET_ID,
                        accepted
                                ? FriendRelationship.NONE
                                : FriendRelationship.REQUEST_SENT
                ),
                accepted
                        ? FriendRequestStatus.ACCEPTED
                        : FriendRequestStatus.PENDING,
                requestedAt,
                accepted ? requestedAt.plusSeconds(60) : null,
                requestedAt.plusSeconds(7 * 24 * 60 * 60),
                roomId
        );
    }

    private FriendRequestPetResponse pet(
            Long petId,
            FriendRelationship relationship
    ) {
        return new FriendRequestPetResponse(
                petId,
                "pet#TAG1",
                "pet",
                null,
                true,
                relationship
        );
    }
}
