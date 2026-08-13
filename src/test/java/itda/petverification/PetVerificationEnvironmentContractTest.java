package itda.petverification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PetVerificationEnvironmentContractTest {

    @Test
    void exampleEnvironmentExplainsThatThePublicDataServiceKeyMustBeDecodedBeforeSingleEncoding() throws Exception {
        String example = Files.readString(Path.of(".env.example"));

        assertThat(example).contains(
                "PET_VERIFICATION_SERVICE_KEY expects the decoded public-data decoding key",
                "app encodes it once for the query.");
    }

    @Test
    void exampleEnvironmentUsesTheExactPetVerificationHmacPlaceholderWithoutARealSecret() throws Exception {
        String example = Files.readString(Path.of(".env.example"));

        assertThat(example).contains(
                "PET_VERIFICATION_HMAC_SECRET=replace-with-a-random-pet-verification-hmac-secret-at-least-32-bytes");
    }
}
