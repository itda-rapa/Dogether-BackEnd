package itda.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.eventhandler.GlobalExceptionHandler;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.dashboard.service.AdminDashboardQueryService;
import itda.user.domain.Role;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AdminDashboardControllerTest {

    private static final long ADMIN_ID = 99L;

    private MockMvc mockMvc;
    private AdminDashboardQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(AdminDashboardQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminDashboardController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();
    }

    @Test
    void explicitIsoDatesArePassedToTheServiceAndWrappedInTheCommonEnvelope()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 8, 18);
        LocalDate to = LocalDate.of(2026, 8, 24);

        mockMvc.perform(get("/admin/dashboard")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("관리자 Dashboard 조회 성공"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(queryService).get(ADMIN_ID, from, to);
    }

    @Test
    void omittedDatesAreDelegatedSoTheClockBasedServiceCanApplyTheDefault() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk());

        verify(queryService).get(ADMIN_ID, null, null);
    }

    @Test
    void malformedDateUsesTheStandardValidationErrorEnvelope() throws Exception {
        mockMvc.perform(get("/admin/dashboard")
                        .param("from", "2026-08-XX")
                        .param("to", "2026-08-24"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void domainDateRangeFailurePreservesItsSpecificErrorCode() throws Exception {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 8, 24);
        when(queryService.get(ADMIN_ID, from, to))
                .thenThrow(new BusinessException(ErrorCode.DATE_RANGE_TOO_LARGE));

        mockMvc.perform(get("/admin/dashboard")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DATE_RANGE_TOO_LARGE"));
    }

    private static class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory
        ) {
            return new CurrentUser(ADMIN_ID, "admin@test.invalid", Role.ADMIN);
        }
    }
}
