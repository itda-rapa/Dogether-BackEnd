package itda.auth.service;

import itda.auth.dto.AuthTokensResponse;
import itda.common.security.service.TokenProvider;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;

    public UserRegistrationService(
            UserRepository userRepository,
            TokenProvider tokenProvider
    ) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthTokensResponse registerAndIssue(User user) {
        User registeredUser = userRepository.saveAndFlush(user);
        return AuthTokensResponse.from(
                tokenProvider.issueTokens(registeredUser)
        );
    }
}
