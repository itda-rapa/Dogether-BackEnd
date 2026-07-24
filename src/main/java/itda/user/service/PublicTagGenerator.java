package itda.user.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.user.repository.UserRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class PublicTagGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_BASE_LENGTH = 25;

    private final SecureRandom random = new SecureRandom();
    private final UserRepository userRepository;

    public PublicTagGenerator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String generate(String nickname) {
        String trimmedNickname = nickname.trim();
        String normalizedNickname = trimmedNickname.substring(
                0,
                Math.min(trimmedNickname.length(), MAX_BASE_LENGTH)
        );
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String publicTag = normalizedNickname + "#" + randomSuffix();
            if (!userRepository.existsByPublicTag(publicTag)) {
                return publicTag;
            }
        }
        throw new BusinessException(ErrorCode.PUBLIC_TAG_GENERATION_FAILED);
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return suffix.toString();
    }
}
