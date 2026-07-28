package itda.common.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.security.dto.IssuedTokens;
import itda.common.security.service.TokenProvider;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenProvider tokenProvider;

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

        @Nested
        @DisplayName("Context: POST /pets를 호출하면")
        class WithPetPost {

            @Test
            @DisplayName("It: UNAUTHORIZED 오류와 401을 반환한다")
            void itReturnsUnauthorizedError() throws Exception {
                mockMvc.perform(post("/pets"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error.code")
                                .value(ErrorCode.UNAUTHORIZED.name()));
            }
        }
    }

    @Nested
    @DisplayName("Describe: 인증되지 않은 Me 요청")
    class DescribeAnonymousMeRequest {

        @Test
        @DisplayName("It: GET /me는 401을 반환한다")
        void getMeReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.UNAUTHORIZED.name()));
        }

        @Test
        @DisplayName("It: PUT /me/active-pet은 401을 반환한다")
        void selectActivePetReturnsUnauthorized() throws Exception {
            mockMvc.perform(put("/me/active-pet"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.UNAUTHORIZED.name()));
        }
    }

    @Nested
    @DisplayName("Describe: 인증되지 않은 내 Pet 목록 요청")
    class DescribeAnonymousMyPetListRequest {

        @Test
        @DisplayName("It: GET /pets/me는 401을 반환한다")
        void getMyPetsReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/pets/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.UNAUTHORIZED.name()));
        }
    }

    @Nested
    @DisplayName("Describe: 비활성 User의 Me 요청")
    class DescribeInactiveUsersMeRequest {

        @Test
        @Transactional
        @DisplayName("It: JwtFilter에서 인증되지 않아 GET /me는 401을 반환한다")
        void getMeReturnsUnauthorized() throws Exception {
            User user = activeUser();
            userRepository.saveAndFlush(user);
            IssuedTokens tokens = tokenProvider.issueTokens(user);
            ReflectionTestUtils.setField(
                    user,
                    "accountStatus",
                    AccountStatus.SUSPENDED
            );
            userRepository.saveAndFlush(user);

            mockMvc.perform(get("/me")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + tokens.accessToken()
                            ))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.UNAUTHORIZED.name()));
        }
    }

    private User activeUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return User.register(
                unique + "@example.com",
                "encoded",
                "사용자",
                "사용자#" + unique.substring(0, 8),
                "4113111500"
        );
    }
}
