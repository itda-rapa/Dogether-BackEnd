package itda.petverification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.service.MyPetQueryService;
import itda.petverification.dto.PetVerificationPrefill;
import itda.petverification.dto.PetVerificationResponse;
import itda.petverification.service.ExistingPetVerificationService;
import itda.petverification.service.PetVerificationIssueService;
import itda.user.domain.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

@WebMvcTest({PetVerificationController.class, ExistingPetVerificationController.class})
@AutoConfigureMockMvc(addFilters = false)
class PetVerificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PetVerificationIssueService issueService;
    @MockitoBean private ExistingPetVerificationService existingService;
    @MockitoBean private MyPetQueryService myPetQueryService;
    @MockitoBean private JwtFilter jwtFilter;

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(7L, "user@example.test", Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void issueEvidenceReturns200RatherThanCreated() throws Exception {
        given(issueService.issue(eq(7L), any())).willReturn(new PetVerificationResponse(
                "synthetic-one-time-token", Instant.parse("2026-08-12T12:15:00Z"),
                new PetVerificationPrefill("Synthetic Pet", "Synthetic Breed", LocalDate.of(2022, 1, 1),
                        PetSex.FEMALE, true)));

        mockMvc.perform(post("/pet-verifications").contentType(MediaType.APPLICATION_JSON).content("""
                {"flowType":"PET_CREATE","identifierType":"REGISTRATION_NUMBER",
                 "identifier":"REG-SYN-001","ownerName":"Synthetic Owner"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationToken").value("synthetic-one-time-token"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-12T12:15:00Z"))
                .andExpect(jsonPath("$.data.petPrefill.nickname").value("Synthetic Pet"))
                .andExpect(jsonPath("$.data.petPrefill.breedName").value("Synthetic Breed"))
                .andExpect(jsonPath("$.data.petPrefill.birthDate").value("2022-01-01"))
                .andExpect(jsonPath("$.data.petPrefill.sex").value("FEMALE"))
                .andExpect(jsonPath("$.data.petPrefill.neutered").value(true));
    }

    @Test
    void existingPetApplyReturns200AndPassesTheOneTimeToken() throws Exception {
        given(myPetQueryService.getMyPet(7L, 31L)).willReturn(verifiedPet());
        mockMvc.perform(post("/pets/{petId}/verification", 31L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petVerificationToken\":\"synthetic-one-time-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.verifiedAt").value("2026-08-12T12:00:00Z"));

        then(existingService).should().apply(7L, 31L, "synthetic-one-time-token");
        then(myPetQueryService).should().getMyPet(7L, 31L);
    }

    @Test
    void issueMapsValidationNoMatchAndProviderUnavailableToTheirContractualStatuses() throws Exception {
        assertIssueError(ErrorCode.VALIDATION_FAILED, 400);
        assertIssueError(ErrorCode.PET_VERIFICATION_NOT_MATCHED, 422);
        assertIssueError(ErrorCode.PET_VERIFICATION_UNAVAILABLE, 503);
    }

    private void assertIssueError(ErrorCode errorCode, int statusCode) throws Exception {
        org.mockito.Mockito.reset(issueService);
        org.mockito.Mockito.doThrow(new BusinessException(errorCode)).when(issueService)
                .issue(eq(7L), any());

        mockMvc.perform(post("/pet-verifications").contentType(MediaType.APPLICATION_JSON).content("""
                {"flowType":"PET_CREATE","identifierType":"REGISTRATION_NUMBER",
                 "identifier":"REG-SYN-001","ownerName":"Synthetic Owner"}
                """))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.error.code").value(errorCode.name()));
    }

    private PetResponse verifiedPet() {
        return new PetResponse(31L, 7L, "pet#A1B2", "owner#C3D4", "Synthetic Pet", null,
                null, null, null, null, null, null, List.of(), null, null,
                PetStatus.ACTIVE, null, true, Instant.parse("2026-08-12T12:00:00Z"), false);
    }
}
