package itda.common.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminPathRejectsNormalUser() throws Exception {
        mockMvc.perform(get("/admin/test")
                        .with(user("user@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPathAllowsAdminThroughAuthorizationLayer() throws Exception {
        mockMvc.perform(get("/admin/test")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Nested
    @DisplayName("Describe: 인증되지 않은 보호된 POST 요청")
    class DescribeAnonymousProtectedPost {

        @Nested
        @DisplayName("Context: POST /neighborhoods를 호출하면")
        class WithNeighborhoodPost {

            @Test
            @DisplayName("It: 401을 반환한다")
            void itReturnsUnauthorized() throws Exception {
                // when
                var result = mockMvc.perform(post("/neighborhoods"));

                // then
                result.andExpect(status().isUnauthorized());
            }
        }

        @Nested
        @DisplayName("Context: POST /auth/logout을 호출하면")
        class WithLogoutPost {

            @Test
            @DisplayName("It: UNAUTHORIZED 오류와 401을 반환한다")
            void itReturnsUnauthorizedError() throws Exception {
                // when
                var result = mockMvc.perform(post("/auth/logout"));

                // then
                result.andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error.code")
                                .value(ErrorCode.UNAUTHORIZED.name()));
            }
        }
    }
}
