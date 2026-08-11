package itda.friend.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.friend.domain.FriendRelationship;
import itda.friend.dto.response.FriendPetListItemResponse;
import itda.friend.dto.response.PetFriendListResponse;
import itda.friend.service.FriendshipDeletionService;
import itda.friend.service.query.FriendshipQueryService;
import itda.user.domain.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PetFriendController.class)
@AutoConfigureMockMvc(addFilters = false)
class PetFriendControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 10L;
    private static final CurrentUser CURRENT_USER =
            new CurrentUser(USER_ID, "user@example.com", Role.USER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendshipQueryService friendshipQueryService;

    @MockitoBean
    private FriendshipDeletionService friendshipDeletionService;

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
    void listsPetFriends() throws Exception {
        given(friendshipQueryService.listFriends(
                USER_ID, PET_ID, null, 20
        )).willReturn(new PetFriendListResponse(
                List.of(new FriendPetListItemResponse(
                        20L,
                        "콩이#A1B2",
                        "콩이",
                        null,
                        false,
                        FriendRelationship.FRIEND
                )),
                CursorPage.of(null, false)
        ));

        mockMvc.perform(get("/pets/{petId}/friends", PET_ID)
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].petId").value(20))
                .andExpect(jsonPath("$.data.items[0].relationship")
                        .value("FRIEND"))
                .andExpect(jsonPath("$.data.page.nextCursor").isEmpty())
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
    }

    @Test
    void malformedLimitReturnsValidationFailed() throws Exception {
        mockMvc.perform(get("/pets/{petId}/friends", PET_ID)
                        .queryParam("limit", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));

        verifyNoInteractions(friendshipQueryService);
    }

    @Test
    void deletesFriendshipWithNoContent() throws Exception {
        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        PET_ID,
                        20L
                ))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        then(friendshipDeletionService).should()
                .deleteFriendship(USER_ID, PET_ID, 20L);
    }

    @Test
    void mapsPetNotFound() throws Exception {
        willThrow(new BusinessException(ErrorCode.PET_NOT_FOUND))
                .given(friendshipDeletionService)
                .deleteFriendship(USER_ID, PET_ID, 20L);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        PET_ID,
                        20L
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    @Test
    void mapsPetNotOwned() throws Exception {
        willThrow(new BusinessException(ErrorCode.PET_NOT_OWNED))
                .given(friendshipDeletionService)
                .deleteFriendship(USER_ID, PET_ID, 20L);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        PET_ID,
                        20L
                ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_OWNED"));
    }

    @Test
    void mapsFriendshipNotFound() throws Exception {
        willThrow(new BusinessException(ErrorCode.FRIENDSHIP_NOT_FOUND))
                .given(friendshipDeletionService)
                .deleteFriendship(USER_ID, PET_ID, 20L);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        PET_ID,
                        20L
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("FRIENDSHIP_NOT_FOUND"));
    }
}
