package itda.user.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.user.domain.User;
import itda.user.dto.MeResponse;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeQueryService {

    private final UserRepository userRepository;

    public MeQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND)
                );

        return MeResponse.from(user);
    }
}
