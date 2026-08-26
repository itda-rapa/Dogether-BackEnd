package itda.auth.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.TokenProvider;
import itda.email.EmailVerificationPurpose;
import itda.email.EmailVerificationService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisional internal API until the M2 password-reset HTTP contract is merged.
 */
@Service
public class PasswordResetService {
    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;

    public PasswordResetService(EmailVerificationService emailVerificationService,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                TokenProvider tokenProvider) {
        this.emailVerificationService = emailVerificationService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public void reset(String rawEmail, String verificationToken, String newPassword) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        emailVerificationService.consume(verificationToken, email, EmailVerificationPurpose.PASSWORD_RESET);
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.hasPasswordCredential()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        tokenProvider.revokeAllForUser(user.getId());
    }
}
