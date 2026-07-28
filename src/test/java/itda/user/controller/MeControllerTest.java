package itda.user.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.pet.service.ActivePetSelectionService;
import itda.user.domain.AccountStatus;
import itda.user.domain.Role;
import itda.user.dto.AccessLevel;
import itda.user.dto.MeResponse;
import itda.user.service.MeQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MeController")
class MeControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeQueryService meQueryService;

    @MockitoBean
    private ActivePetSelectionService activePetSelectionService;

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

    @Nested
    @DisplayName("Describe: GET /me")
    class DescribeGetMe {

        @Test
        @DisplayName("It: CurrentUser ID로 조회하고 성공 Envelope를 반환한다")
        void itReturnsMeEnvelope() throws Exception {
            given(meQueryService.getMe(USER_ID)).willReturn(meResponse(null));

            var result = mockMvc.perform(get("/me"));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userId").value(USER_ID))
                    .andExpect(jsonPath("$.data.email")
                            .value("user@example.com"))
                    .andExpect(jsonPath("$.data.nickname")
                            .value("사용자"))
                    .andExpect(jsonPath("$.data.publicTag")
                            .value("사용자#A7K2"))
                    .andExpect(jsonPath("$.data.role")
                            .value("USER"))
                    .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.accessLevel").value("L1"))
                    .andExpect(jsonPath("$.data.neighborhoodCode")
                            .value("4113111500"))
                    .andExpect(jsonPath("$.data.activePetId").isEmpty());
            then(meQueryService).should().getMe(USER_ID);
        }
    }

    @Nested
    @DisplayName("Describe: PUT /me/active-pet")
    class DescribeSelectActivePet {

        @Nested
        @DisplayName("Context: 유효한 Pet ID를 요청하면")
        class ContextWithValidPetId {

            @Test
            @DisplayName("It: 선택 후 내 정보를 다시 조회해 성공 Envelope를 반환한다")
            void itSelectsAndReturnsUpdatedMe() throws Exception {
                given(meQueryService.getMe(USER_ID))
                        .willReturn(meResponse(PET_ID));

                var result = mockMvc.perform(put("/me/active-pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "petId": 2
                                }
                                """));

                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.activePetId").value(PET_ID))
                        .andExpect(jsonPath("$.data.accessLevel").value("L2"));
                InOrder order = inOrder(
                        activePetSelectionService,
                        meQueryService
                );
                order.verify(activePetSelectionService)
                        .selectActivePet(USER_ID, PET_ID);
                order.verify(meQueryService).getMe(USER_ID);
            }
        }

        @Nested
        @DisplayName("Context: Pet ID가 null이면")
        class ContextWithNullPetId {

            @Test
            @DisplayName("It: 400 VALIDATION_FAILED를 반환하고 Service를 호출하지 않는다")
            void itReturnsValidationFailed() throws Exception {
                var result = mockMvc.perform(put("/me/active-pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "petId": null
                                }
                                """));

                result.andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.error.code")
                                .value(ErrorCode.VALIDATION_FAILED.name()));
                then(activePetSelectionService).shouldHaveNoInteractions();
                then(meQueryService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 선택 Service가 BusinessException을 반환하면")
        class ContextWithBusinessException {

            @Test
            @DisplayName("It: 기존 Error Envelope와 HTTP Status를 유지한다")
            void itReturnsBusinessErrorEnvelope() throws Exception {
                org.mockito.BDDMockito.willThrow(
                        new BusinessException(ErrorCode.PET_NOT_OWNED)
                ).given(activePetSelectionService)
                        .selectActivePet(USER_ID, PET_ID);

                var result = mockMvc.perform(put("/me/active-pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "petId": 2
                                }
                                """));

                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.error.code")
                                .value(ErrorCode.PET_NOT_OWNED.name()));
                then(meQueryService).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: Active Pet 선택 중 잠금 충돌이 발생하면")
        class ContextWithLockConflict {

            @Test
            @DisplayName("It: 409 CONCURRENT_UPDATE_CONFLICT를 반환한다")
            void itReturnsConcurrentUpdateConflict() throws Exception {
                org.mockito.BDDMockito.willThrow(
                        new PessimisticLockingFailureException(
                                "lock conflict"
                        )
                ).given(activePetSelectionService)
                        .selectActivePet(USER_ID, PET_ID);

                var result = mockMvc.perform(put("/me/active-pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "petId": 2
                                }
                                """));

                result.andExpect(status().isConflict())
                        .andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.error.code")
                                .value(
                                        ErrorCode
                                                .CONCURRENT_UPDATE_CONFLICT
                                                .name()
                                ));
                then(meQueryService).shouldHaveNoInteractions();
            }
        }
    }

    private MeResponse meResponse(Long activePetId) {
        return new MeResponse(
                USER_ID,
                "user@example.com",
                "사용자",
                "사용자#A7K2",
                Role.USER,
                AccountStatus.ACTIVE,
                activePetId == null ? AccessLevel.L1 : AccessLevel.L2,
                "4113111500",
                activePetId
        );
    }
}
