package itda.meetingverification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.security.CurrentUser;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.service.MeetingVerificationService;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.UUID;
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

/**
 * 위치 제출 요청의 필수 필드 검증을 컨트롤러 경계에서 확인한다.
 *
 * <p>latitude/longitude/accuracyMeters 는 wrapper + {@code @NotNull} 이므로 JSON 필드 누락은
 * 400 {@code VALIDATION_FAILED} 로 막고, 실제 {@code 0.0} 값 자체는 Service 까지 전달된다.
 * 검증 실패 시 {@link MeetingVerificationService#submit} 은 호출되지 않는다.
 */
@WebMvcTest(MeetingVerificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MeetingVerificationController — 요청 필수값 검증")
class MeetingVerificationControllerTest {

    private static final long USER_ID = 1L;
    private static final long CARD_ID = 51L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingVerificationService meetingVerificationService;

    @MockitoBean
    private itda.common.filter.JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        CurrentUser currentUser = new CurrentUser(USER_ID, "user@example.com", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        currentUser, null, currentUser.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("latitude 누락은 400 VALIDATION_FAILED 이고 Service 를 호출하지 않는다")
    void missingLatitudeIsRejected() throws Exception {
        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "longitude": 126.978,
                                  "accuracyMeters": 24.5,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(meetingVerificationService);
    }

    @Test
    @DisplayName("longitude 누락은 400 VALIDATION_FAILED 이고 Service 를 호출하지 않는다")
    void missingLongitudeIsRejected() throws Exception {
        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 37.5665,
                                  "accuracyMeters": 24.5,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(meetingVerificationService);
    }

    @Test
    @DisplayName("accuracyMeters 누락은 400 VALIDATION_FAILED 이고 Service 를 호출하지 않는다")
    void missingAccuracyMetersIsRejected() throws Exception {
        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(meetingVerificationService);
    }

    @Test
    @DisplayName("clientRequestId 누락은 400 VALIDATION_FAILED 이고 Service 를 호출하지 않는다")
    void missingClientRequestIdIsRejected() throws Exception {
        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "accuracyMeters": 24.5,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(meetingVerificationService);
    }

    @Test
    @DisplayName("실제 0.0 좌표 값은 DTO 검증을 통과해 Service 에 전달된다")
    void zeroCoordinatesPassValidationAndReachService() throws Exception {
        given(meetingVerificationService.submit(eq(USER_ID), eq(CARD_ID), any()))
                .willReturn(new MeetingVerificationResult(
                        CARD_ID, 12L, false, null, false, null, null));

        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 0.0,
                                  "longitude": 0.0,
                                  "accuracyMeters": 0.0,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        verify(meetingVerificationService).submit(eq(USER_ID), eq(CARD_ID), any());
    }
}
