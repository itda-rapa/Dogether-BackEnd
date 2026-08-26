package itda.oauth.service;

import itda.user.service.PublicTagGenerator;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthSignupService {

    private static final int PUBLIC_TAG_SAVE_ATTEMPTS = 5;
    private static final String PUBLIC_TAG_UNIQUE_CONSTRAINT = "uk_users_public_tag";
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email_lower";
    private static final String IDENTITY_SUBJECT_UNIQUE_CONSTRAINT =
            "uk_oauth_identities_provider_subject";
    private static final String IDENTITY_USER_UNIQUE_CONSTRAINT =
            "uk_oauth_identities_user_provider";

    private final PublicTagGenerator publicTagGenerator;
    private final OAuthSignupTransactionService transactionService;

    public OAuthSignupService(
            PublicTagGenerator publicTagGenerator,
            OAuthSignupTransactionService transactionService
    ) {
        this.publicTagGenerator = publicTagGenerator;
        this.transactionService = transactionService;
    }

    /**
     * The completion (normally Dogether token issuance) runs in the attempt transaction.  Only a
     * public-tag collision retries; all other uniqueness races become a safe restart conflict.
     */
    @Transactional(propagation = Propagation.NEVER)
    public <T> T complete(
            OAuthSignupCommand command,
            OAuthSignupCompletion<T> completion
    ) {
        for (int attempt = 0; attempt < PUBLIC_TAG_SAVE_ATTEMPTS; attempt++) {
            String publicTag = publicTagGenerator.generate(requiredNickname(command));
            try {
                return transactionService.completeAttempt(command, publicTag, completion);
            } catch (DataIntegrityViolationException exception) {
                if (isConstraintViolation(exception, PUBLIC_TAG_UNIQUE_CONSTRAINT)) {
                    continue;
                }
                if (isIdentityOrEmailUniqueViolation(exception)) {
                    // The REQUIRES_NEW attempt has already rolled back. A known email/identity
                    // uniqueness collision is a safe restart conflict, never a public-tag retry
                    // or a link-decision result.
                    throw failure(OAuthFlowFailure.CONCURRENT_UPDATE_CONFLICT);
                }
                throw exception;
            }
        }
        throw failure(OAuthFlowFailure.PUBLIC_TAG_GENERATION_FAILED);
    }

    private String requiredNickname(OAuthSignupCommand command) {
        if (command == null || command.nickname() == null || command.nickname().trim().isEmpty()) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
        return command.nickname().trim();
    }

    private boolean isIdentityOrEmailUniqueViolation(DataIntegrityViolationException exception) {
        return isConstraintViolation(exception, EMAIL_UNIQUE_CONSTRAINT)
                || isConstraintViolation(exception, IDENTITY_SUBJECT_UNIQUE_CONSTRAINT)
                || isConstraintViolation(exception, IDENTITY_USER_UNIQUE_CONSTRAINT);
    }

    private boolean isConstraintViolation(
            DataIntegrityViolationException exception,
            String expectedConstraint
    ) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && expectedConstraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT)
                    .contains(expectedConstraint.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private OAuthFlowException failure(OAuthFlowFailure failure) {
        return new OAuthFlowException(failure);
    }
}
