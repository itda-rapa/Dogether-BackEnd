package itda.pet.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class PetPublicTagGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_BASE_CODE_POINTS = 25;
    private static final int SUFFIX_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    public String generate(String nickname) {
        int codePointCount = nickname.codePointCount(0, nickname.length());
        int baseEndIndex = nickname.offsetByCodePoints(
                0,
                Math.min(codePointCount, MAX_BASE_CODE_POINTS)
        );
        return nickname.substring(0, baseEndIndex) + "#" + randomSuffix();
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int index = 0; index < SUFFIX_LENGTH; index++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return suffix.toString();
    }
}
