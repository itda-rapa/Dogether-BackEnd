package itda.chat.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.dto.response.OpenChatInviteResponse;
import itda.chat.service.OpenChatInviteService;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
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

@WebMvcTest(OpenChatInviteController.class)
@AutoConfigureMockMvc(addFilters = false)
class OpenChatInviteControllerTest {

    private static final CurrentUser CURRENT_USER =
            new CurrentUser(1L, "user@example.com", Role.USER);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private OpenChatInviteService openChatInviteService;
    @MockitoBean
    private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        CURRENT_USER, null, CURRENT_USER.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invitesFriendPet() throws Exception {
        given(openChatInviteService.invite(1L, 7L, 20L)).willReturn(
                new OpenChatInviteResponse(7L, 20L, true, 3));

        mockMvc.perform(post("/chat/rooms/open/{roomId}/invites", 7L)
                        .contentType("application/json")
                        .content("{\"targetPetId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(7))
                .andExpect(jsonPath("$.data.targetPetId").value(20))
                .andExpect(jsonPath("$.data.joined").value(true))
                .andExpect(jsonPath("$.data.activeParticipants").value(3));

        then(openChatInviteService).should().invite(1L, 7L, 20L);
    }

    @Test
    void rejectsMissingTargetPetId() throws Exception {
        mockMvc.perform(post("/chat/rooms/open/{roomId}/invites", 7L)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        then(openChatInviteService).shouldHaveNoInteractions();
    }
}
