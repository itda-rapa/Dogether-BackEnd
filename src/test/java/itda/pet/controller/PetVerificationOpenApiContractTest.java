package itda.pet.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import itda.common.security.CurrentUser;
import itda.pet.dto.PetCreateRequest;
import itda.petverification.controller.PetVerificationController;
import itda.petverification.dto.PetVerificationRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PetVerificationOpenApiContractTest {

    @Test
    void petCreateDocumentsOptionalVerificationTokenExampleAndVerificationFailuresWithoutChangingCreatedStatus() throws Exception {
        Method method = PetSwaggerSupporter.class.getMethod("createPet", CurrentUser.class, PetCreateRequest.class);
        RequestBody requestBody = method.getAnnotation(RequestBody.class);
        Map<String, String> responses = responses(method);

        assertThat(requestBody.content()).singleElement()
                .satisfies(content -> assertThat(content.examples()).singleElement()
                        .satisfies(example -> assertThat(example.value())
                                .contains("\"petVerificationToken\"", "pet-verification-token-placeholder")));
        assertThat(responses).containsEntry("201", "Pet 등록 성공")
                .containsEntry("400", "VALIDATION_FAILED")
                .containsEntry("409", "PET_VERIFICATION_CONFLICT")
                .containsEntry("410", "PET_VERIFICATION_TOKEN_INVALID")
                .containsEntry("503", "PET_VERIFICATION_UNAVAILABLE");
    }

    @Test
    void evidenceIssuerDocumentsValidationAndNoMatchContracts() throws Exception {
        Method method = PetVerificationController.class.getMethod("issue", CurrentUser.class,
                PetVerificationRequest.class);

        assertThat(responses(method)).containsEntry("200", "인증 evidence 토큰 발급 성공")
                .containsEntry("400", "VALIDATION_FAILED")
                .containsEntry("422", "PET_VERIFICATION_NOT_MATCHED")
                .containsEntry("503", "PET_VERIFICATION_UNAVAILABLE");
    }

    private Map<String, String> responses(Method method) {
        return Arrays.stream(method.getAnnotationsByType(ApiResponse.class))
                .collect(Collectors.toMap(ApiResponse::responseCode, ApiResponse::description));
    }
}
