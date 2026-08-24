package itda.safety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.dto.response.CursorPage;
import itda.common.eventhandler.GlobalExceptionHandler;
import itda.common.security.CurrentUser;
import itda.safety.dto.SafetyCaseActionRequest;
import itda.safety.dto.SafetyCasePageResponse;
import itda.safety.dto.SafetyEvidencePageResponse;
import itda.safety.service.AdminSafetyActionService;
import itda.safety.service.AdminSafetyEvidenceService;
import itda.safety.service.AdminSafetyQueryService;
import itda.user.domain.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AdminSafetyControllerTest {

    private MockMvc mockMvc;
    private AdminSafetyQueryService queryService;
    private AdminSafetyEvidenceService evidenceService;

    @BeforeEach
    void setUp() {
        queryService = mock(AdminSafetyQueryService.class);
        AdminSafetyActionService actionService = mock(AdminSafetyActionService.class);
        evidenceService = mock(AdminSafetyEvidenceService.class);
        AdminSafetyController controller = new AdminSafetyController(
                queryService, actionService, evidenceService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MethodValidationPostProcessor methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        Object validatedController = methodValidation.postProcessAfterInitialization(
                controller, "adminSafetyController");
        mockMvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();
    }

    @Test
    void listUsesApiResponseEnvelopeAndOpenDefault() throws Exception {
        when(queryService.list(anyLong(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new SafetyCasePageResponse(List.of(), CursorPage.of(null, false)));

        mockMvc.perform(get("/admin/safety/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
    }

    @Test
    void evidenceRequiresNonBlankPurpose() throws Exception {
        mockMvc.perform(get("/admin/safety/cases/1/evidence").param("purpose", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void evidenceReturnsEnvelopeWithoutSensitiveFields() throws Exception {
        when(evidenceService.evidence(eq(99L), eq(1L), eq("review"), any(), eq(20)))
                .thenReturn(new SafetyEvidencePageResponse(List.of(), CursorPage.of(null, false)));

        mockMvc.perform(get("/admin/safety/cases/1/evidence").param("purpose", "review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.metadata").doesNotExist())
                .andExpect(jsonPath("$.data.mediaUrl").doesNotExist());
    }

    @Test
    void actionBodyIsValidated() throws Exception {
        mockMvc.perform(post("/admin/safety/cases/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static class CurrentUserArgumentResolver
            implements org.springframework.web.method.support.HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(
                org.springframework.core.MethodParameter parameter
        ) {
            return parameter.hasParameterAnnotation(
                    org.springframework.security.core.annotation.AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                org.springframework.core.MethodParameter parameter,
                org.springframework.web.method.support.ModelAndViewContainer container,
                org.springframework.web.context.request.NativeWebRequest request,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory
        ) {
            return new CurrentUser(99L, "admin@test.invalid", Role.ADMIN);
        }
    }
}
