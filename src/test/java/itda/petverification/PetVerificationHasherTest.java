package itda.petverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PetVerificationHasherTest {

    @Test
    void failsFastOutsideTheTestProfileForMissingBlankOrUnresolvedSecret() {
        assertMissingSecret(null);
        assertMissingSecret("   ");
        assertMissingSecret("${PET_VERIFICATION_HMAC_SECRET}");
        assertMissingSecret("replace-with-a-random-pet-verification-hmac-secret-at-least-32-bytes");
    }

    @Test
    void rejectsA31ByteAsciiSecretOutsideTheTestProfile() {
        assertMissingSecret("a".repeat(31));
    }

    @Test
    void acceptsSecretsAtOrAboveThe32Utf8ByteBoundaryOutsideTheTestProfile() {
        assertValidSecret("a".repeat(32));
        assertValidSecret("a".repeat(33));
        assertValidSecret("가".repeat(11));
    }

    @Test
    void testProfileUsesOnlyDeterministicNonRealFallbackForAnAbsentSecret() {
        PetVerificationProperties properties = properties(null);
        PetVerificationHasher first = new PetVerificationHasher(properties, testEnvironment());
        PetVerificationHasher second = new PetVerificationHasher(properties, testEnvironment());

        assertThat(first.registrationNumber("REG-SYN-001"))
                .hasSize(64).isEqualTo(second.registrationNumber("REG-SYN-001"));
        assertThat(first.registrationNumber("REG-SYN-001"))
                .isNotEqualTo(first.token("REG-SYN-001"));
    }

    private void assertMissingSecret(String secret) {
        assertThatThrownBy(() -> new PetVerificationHasher(properties(secret), new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PET_VERIFICATION_HMAC_SECRET");
    }

    private void assertValidSecret(String secret) {
        PetVerificationHasher hasher = new PetVerificationHasher(properties(secret), new MockEnvironment());

        assertThat(hasher.registrationNumber("REG-SYN-BOUNDARY")).matches("[0-9a-f]{64}");
    }

    private PetVerificationProperties properties(String secret) {
        return new PetVerificationProperties(secret, "synthetic-service-key", null, null, null);
    }

    private MockEnvironment testEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment;
    }
}
