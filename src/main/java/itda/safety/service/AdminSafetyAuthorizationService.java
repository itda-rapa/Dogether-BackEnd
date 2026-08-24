package itda.safety.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.user.domain.Role;
import itda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSafetyAuthorizationService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void requireActiveAdmin(long userId) {
        boolean allowed = userId > 0 && userRepository.findById(userId)
                .map(user -> user.isActive()
                        && (user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN))
                .orElse(false);
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
