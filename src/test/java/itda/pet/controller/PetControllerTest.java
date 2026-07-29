package itda.pet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.filter.JwtFilter;
import itda.common.security.CurrentUser;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.service.ActivePetAssignmentStatus;
import itda.pet.service.MyPetQueryService;
import itda.pet.service.PetCreateCommand;
import itda.pet.service.PetCreationResult;
import itda.pet.service.PetCreationService;
import itda.user.domain.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

@WebMvcTest(PetController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PetController")
class PetControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PetCreationService petCreationService;

    @MockitoBean
    private MyPetQueryService myPetQueryService;

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
    @DisplayName("Describe: POST /pets")
    class DescribeCreatePet {

        @Test
        @DisplayName("It: ASSIGNED 상태와 최신 Pet 응답을 201로 반환한다")
        void itCreatesPetAndReturnsAssignedResponse() throws Exception {
            given(petCreationService.create(eq(USER_ID), any(PetCreateCommand.class)))
                    .willReturn(new PetCreationResult(
                            PET_ID,
                            ActivePetAssignmentStatus.ASSIGNED
                    ));
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willReturn(petResponse(true));

            var result = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequestJson()));

            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pet.petId").value(PET_ID))
                    .andExpect(jsonPath("$.data.pet.nickname").value("몽이"))
                    .andExpect(jsonPath("$.data.pet.active").value(true))
                    .andExpect(jsonPath("$.data.activeAssignmentStatus")
                            .value("ASSIGNED"))
                    .andExpect(jsonPath("$.error").isEmpty())
                    .andExpect(jsonPath("$.data.pet.firstPetCandidate")
                            .doesNotExist())
                    .andExpect(jsonPath("$.data.pet.version").doesNotExist())
                    .andExpect(jsonPath("$.data.pet.owner").doesNotExist())
                    .andExpect(jsonPath("$.data.pet.profileAsset").doesNotExist());
            InOrder order = inOrder(petCreationService, myPetQueryService);
            order.verify(petCreationService)
                    .create(eq(USER_ID), any(PetCreateCommand.class));
            order.verify(myPetQueryService).getMyPet(USER_ID, PET_ID);
        }

        @Test
        @DisplayName("It: NOT_APPLICABLE이어도 조회 결과가 active=true면 그대로 반환한다")
        void itReturnsActualActiveStateIndependentlyFromAssignmentStatus()
                throws Exception {
            given(petCreationService.create(eq(USER_ID), any(PetCreateCommand.class)))
                    .willReturn(new PetCreationResult(
                            PET_ID,
                            ActivePetAssignmentStatus.NOT_APPLICABLE
                    ));
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willReturn(petResponse(true));

            var result = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequestJson()));

            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.activeAssignmentStatus")
                            .value("NOT_APPLICABLE"))
                    .andExpect(jsonPath("$.data.pet.active").value(true));
        }

        @Test
        @DisplayName("It: RETRY_REQUIRED도 Pet과 함께 201로 반환한다")
        void itReturnsRetryRequiredAsCreatedResponse() throws Exception {
            given(petCreationService.create(eq(USER_ID), any(PetCreateCommand.class)))
                    .willReturn(new PetCreationResult(
                            PET_ID,
                            ActivePetAssignmentStatus.RETRY_REQUIRED
                    ));
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willReturn(petResponse(false));

            var result = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequestJson()));

            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.pet.petId").value(PET_ID))
                    .andExpect(jsonPath("$.data.activeAssignmentStatus")
                            .value("RETRY_REQUIRED"));
        }

        @Test
        @DisplayName("It: 잘못된 요청은 400으로 반환하고 Service를 호출하지 않는다")
        void itRejectsInvalidRequestBeforeServiceInvocation() throws Exception {
            var blankNickname = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "nickname": " "
                            }
                            """));
            blankNickname.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.VALIDATION_FAILED.name()));

            var emojiNickname = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "nickname": "몽이😀"
                            }
                            """));
            emojiNickname.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.VALIDATION_FAILED.name()));
            then(petCreationService).shouldHaveNoInteractions();
            then(myPetQueryService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("It: 생성 Service의 일반 오류는 그대로 반환하고 조회하지 않는다")
        void itPropagatesCreationFailureWithoutQueryingPet() throws Exception {
            org.mockito.BDDMockito.willThrow(
                    new BusinessException(ErrorCode.PET_LIMIT_EXCEEDED)
            ).given(petCreationService)
                    .create(eq(USER_ID), any(PetCreateCommand.class));

            var result = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequestJson()));

            result.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.PET_LIMIT_EXCEEDED.name()));
            then(myPetQueryService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName(
                "It: PetCreationService에서 전파된 잠금 충돌은 "
                        + "409 CONCURRENT_UPDATE_CONFLICT를 반환하고 "
                        + "Pet을 조회하지 않는다"
        )
        void itReturnsConflictWithoutQueryWhenCreationServicePropagatesLockFailure()
                throws Exception {
            PessimisticLockingFailureException exception =
                    new PessimisticLockingFailureException(
                            "concurrent update"
                    );
            given(petCreationService.create(
                    eq(USER_ID),
                    any(PetCreateCommand.class)
            )).willThrow(exception);

            var result = mockMvc.perform(post("/pets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequestJson()));

            result.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(
                                    ErrorCode.CONCURRENT_UPDATE_CONFLICT.name()
                            ));
            then(myPetQueryService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("Describe: GET /pets/me")
    class DescribeGetMyPets {

        @Test
        @DisplayName("It: 내 Pet 목록을 200 배열 응답으로 반환한다")
        void itReturnsMyPetList() throws Exception {
            given(myPetQueryService.getMyPets(USER_ID)).willReturn(List.of(
                    petResponse(PET_ID, PetStatus.ACTIVE, true),
                    petResponse(3L, PetStatus.SUSPENDED, false)
            ));

            var result = mockMvc.perform(get("/pets/me"));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].petId").value(PET_ID))
                    .andExpect(jsonPath("$.data[0].active").value(true))
                    .andExpect(jsonPath("$.data[1].petId").value(3L))
                    .andExpect(jsonPath("$.data[1].status")
                            .value("SUSPENDED"))
                    .andExpect(jsonPath("$.error").isEmpty())
                    .andExpect(jsonPath("$.data[0].owner").doesNotExist())
                    .andExpect(jsonPath("$.data[0].version").doesNotExist());
            then(myPetQueryService).should().getMyPets(USER_ID);
            then(myPetQueryService).should(never())
                    .getMyPet(any(), any());
        }

        @Test
        @DisplayName("It: Pet이 없어도 빈 배열을 200으로 반환한다")
        void itReturnsEmptyArrayWhenNoPetsExist() throws Exception {
            given(myPetQueryService.getMyPets(USER_ID)).willReturn(List.of());

            var result = mockMvc.perform(get("/pets/me"));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.error").isEmpty());
            then(myPetQueryService).should().getMyPets(USER_ID);
        }
    }

    @Nested
    @DisplayName("Describe: GET /pets/{petId}")
    class DescribeGetMyPet {

        @Test
        @DisplayName("It: 본인 소유 Pet 상세를 200으로 반환한다")
        void itReturnsMyPetDetail() throws Exception {
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willReturn(petResponse(true));

            var result = mockMvc.perform(get("/pets/{petId}", PET_ID));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.petId").value(PET_ID))
                    .andExpect(jsonPath("$.data.ownerUserId").value(USER_ID))
                    .andExpect(jsonPath("$.data.nickname").value("몽이"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.active").value(true))
                    .andExpect(jsonPath("$.data.profileUrl").isEmpty())
                    .andExpect(jsonPath("$.data.verified").value(false))
                    .andExpect(jsonPath("$.data.verifiedAt").isEmpty())
                    .andExpect(jsonPath("$.error").isEmpty())
                    .andExpect(jsonPath("$.data.owner").doesNotExist())
                    .andExpect(jsonPath("$.data.version").doesNotExist());
            then(myPetQueryService).should().getMyPet(USER_ID, PET_ID);
        }

        @Test
        @DisplayName("It: 본인 소유 SUSPENDED Pet도 200으로 반환한다")
        void itReturnsSuspendedPet() throws Exception {
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willReturn(petResponse(
                            PET_ID,
                            PetStatus.SUSPENDED,
                            true
                    ));

            var result = mockMvc.perform(get("/pets/{petId}", PET_ID));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.data.active").value(true));
        }

        @Test
        @DisplayName("It: Pet이 없으면 PET_NOT_FOUND를 반환한다")
        void itReturnsNotFoundWhenPetDoesNotExist() throws Exception {
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willThrow(new BusinessException(ErrorCode.PET_NOT_FOUND));

            var result = mockMvc.perform(get("/pets/{petId}", PET_ID));

            result.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.PET_NOT_FOUND.name()));
        }

        @Test
        @DisplayName("It: 미삭제 타인 Pet은 PET_NOT_OWNED를 반환한다")
        void itReturnsForbiddenWhenPetIsOwnedByAnotherUser() throws Exception {
            given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                    .willThrow(new BusinessException(ErrorCode.PET_NOT_OWNED));

            var result = mockMvc.perform(get("/pets/{petId}", PET_ID));

            result.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code")
                            .value(ErrorCode.PET_NOT_OWNED.name()));
        }
    }

    private String validRequestJson() {
        return """
                {
                  "nickname": "몽이",
                  "breedName": "말티즈",
                  "sex": "FEMALE",
                  "neutered": true,
                  "birthDate": "2020-01-02",
                  "weightKg": 3.40,
                  "sizeCode": "SMALL",
                  "bio": "사람을 좋아해요.",
                  "personalityTags": ["친화적"],
                  "careNote": "닭고기 알레르기"
                }
                """;
    }

    private PetResponse petResponse(boolean active) {
        return petResponse(PET_ID, PetStatus.ACTIVE, active);
    }

    private PetResponse petResponse(
            Long petId,
            PetStatus status,
            boolean active
    ) {
        return new PetResponse(
                petId,
                USER_ID,
                "몽이#B8M3",
                "보호자#A7K2",
                "몽이",
                "말티즈",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 1, 2),
                new BigDecimal("3.40"),
                PetSizeCode.SMALL,
                "사람을 좋아해요.",
                List.of("친화적"),
                "닭고기 알레르기",
                null,
                status,
                null,
                false,
                null,
                active
        );
    }
}
