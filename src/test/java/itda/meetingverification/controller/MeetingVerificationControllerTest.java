package itda.meetingverification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.security.CurrentUser;
import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import itda.meetingverification.dto.ConfirmationCodeCreateResult;
import itda.meetingverification.dto.ConfirmationCodeResult;
import itda.meetingverification.dto.MeetingVerificationResult;
import itda.meetingverification.service.MeetingConfirmationCodeService;
import itda.meetingverification.service.MeetingVerificationService;
import itda.user.domain.Role;
import java.time.Instant;
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
 * 위치 제출 요청의 필수 필드 검증과 응답 메시지·신규 필드를 컨트롤러 경계에서 확인한다.
 *
 * <p>latitude/longitude/accuracyMeters 는 wrapper + {@code @NotNull} 이므로 JSON 필드 누락은
 * 400 {@code VALIDATION_FAILED} 로 막고, 실제 {@code 0.0} 값 자체는 Service 까지 전달된다.
 * 검증 실패 시 {@link MeetingVerificationService#submit} 은 호출되지 않는다.
 */
@WebMvcTest(MeetingVerificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MeetingVerificationController — 요청 필수값 검증·응답")
class MeetingVerificationControllerTest {

    private static final long USER_ID = 1L;
    private static final long CARD_ID = 51L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingVerificationService meetingVerificationService;

    @MockitoBean
    private MeetingConfirmationCodeService codeService;

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

        verifyNoInteractions(meetingVerificationService, codeService);
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

        verifyNoInteractions(meetingVerificationService, codeService);
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

        verifyNoInteractions(meetingVerificationService, codeService);
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

        verifyNoInteractions(meetingVerificationService, codeService);
    }

    @Test
    @DisplayName("실제 0.0 좌표 값은 DTO 검증을 통과해 Service 에 전달된다")
    void zeroCoordinatesPassValidationAndReachService() throws Exception {
        given(meetingVerificationService.submit(eq(USER_ID), eq(CARD_ID), any()))
                .willReturn(new MeetingVerificationResult(
                        CARD_ID, 12L, MeetingVerificationApiStatus.WAITING_COUNTERPART,
                        false, null, false, null, null, false, null));

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

    @Test
    @DisplayName("LOW_ACCURACY 제출은 codeRequired=true 와 전용 메시지를 반환한다")
    void lowAccuracyResponseUsesCodeRequiredMessage() throws Exception {
        given(meetingVerificationService.submit(eq(USER_ID), eq(CARD_ID), any()))
                .willReturn(new MeetingVerificationResult(
                        CARD_ID, 12L, MeetingVerificationApiStatus.CODE_REQUIRED,
                        false, null, false, null, null, true, null));

        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "accuracyMeters": 60.0,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("위치 정확도가 낮아 확인 코드가 필요합니다."))
                .andExpect(jsonPath("$.data.codeRequired").value(true))
                .andExpect(jsonPath("$.data.confirmed").value(false))
                .andExpect(jsonPath("$.data.meetingId").isEmpty());
    }

    @Test
    @DisplayName("일반 GPS 대기는 codeRequired=false 와 기존 대기 메시지를 유지한다")
    void waitingResponseUsesWaitingMessage() throws Exception {
        given(meetingVerificationService.submit(eq(USER_ID), eq(CARD_ID), any()))
                .willReturn(new MeetingVerificationResult(
                        CARD_ID, 12L, MeetingVerificationApiStatus.WAITING_COUNTERPART,
                        false, null, false, null, null, false, null));

        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "accuracyMeters": 24.5,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("상대방의 확인을 기다리고 있습니다."))
                .andExpect(jsonPath("$.data.codeRequired").value(false))
                .andExpect(jsonPath("$.data.confirmed").value(false));
    }

    @Test
    @DisplayName("GPS 확정 응답은 distanceMeters 와 codeRequired=false 를 포함한다")
    void confirmedResponseIncludesDistanceMeters() throws Exception {
        Instant confirmedAt = Instant.parse("2026-08-20T09:02:00Z");
        given(meetingVerificationService.submit(eq(USER_ID), eq(CARD_ID), any()))
                .willReturn(new MeetingVerificationResult(
                        CARD_ID, 12L, MeetingVerificationApiStatus.GPS_CONFIRMED,
                        true, 61L, true, MeetingVerificationMethod.GPS,
                        confirmedAt, false, 42.7));

        mockMvc.perform(post("/meeting-cards/{cardId}/meeting-verifications", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "accuracyMeters": 24.5,
                                  "capturedAt": "2026-08-20T09:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("만남이 확인되었습니다."))
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.codeRequired").value(false))
                .andExpect(jsonPath("$.data.distanceMeters").value(42.7));
    }

    // ── Confirmation Code API ─────────────────────────────────────────────

    @Test
    @DisplayName("코드 발급은 201 이고 CurrentUser.id 를 Service 에 전달한다")
    void issueCodeReturnsCreatedAndPassesUserId() throws Exception {
        given(codeService.issue(eq(USER_ID), eq(CARD_ID)))
                .willReturn(new ConfirmationCodeCreateResult("4821", Instant.now()));

        mockMvc.perform(post("/meeting-cards/{cardId}/confirmation-codes", CARD_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("4821"));

        verify(codeService).issue(eq(USER_ID), eq(CARD_ID));
    }

    @Test
    @DisplayName("verify code 형식 오류(123/12345/abcd)는 400 이고 Service 를 호출하지 않는다")
    void verifyCodeInvalidFormatIsRejected() throws Exception {
        for (String code : new String[]{"123", "12345", "abcd"}) {
            mockMvc.perform(post("/meeting-cards/{cardId}/confirmation-codes/verify", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\": \"" + code + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()));
        }

        verifyNoInteractions(codeService);
    }

    @Test
    @DisplayName("정상 0123 verify 는 Service 에 정확히 전달된다")
    void verifyCodeValidPassesToService() throws Exception {
        given(codeService.verify(eq(USER_ID), eq(CARD_ID), eq("0123")))
                .willReturn(new ConfirmationCodeResult(CARD_ID, "WAITING_ISSUER_CONFIRMATION",
                        null, MeetingVerificationMethod.CODE, null));

        mockMvc.perform(post("/meeting-cards/{cardId}/confirmation-codes/verify", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"0123\"}"))
                .andExpect(status().isOk());

        verify(codeService).verify(eq(USER_ID), eq(CARD_ID), eq("0123"));
    }

    @Test
    @DisplayName("confirm 성공은 200 이고 CurrentUser.id 를 Service 에 전달한다")
    void confirmCodeReturnsOkAndPassesUserId() throws Exception {
        given(codeService.confirm(eq(USER_ID), eq(CARD_ID)))
                .willReturn(new ConfirmationCodeResult(CARD_ID, "CONFIRMED", 61L,
                        MeetingVerificationMethod.CODE, Instant.now()));

        mockMvc.perform(post("/meeting-cards/{cardId}/confirmation-codes/confirm", CARD_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        verify(codeService).confirm(eq(USER_ID), eq(CARD_ID));
    }
}
