package itda.user.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.User;
import itda.user.dto.MeResponse;
import itda.user.dto.MeUpdateCommand;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeUpdateService {

    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("1.00");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("500.00");

    private final UserRepository userRepository;
    private final NeighborhoodRepository neighborhoodRepository;

    public MeUpdateService(
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository
    ) {
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
    }

    @Transactional
    public MeResponse update(Long userId, MeUpdateCommand command) {
        validateCommand(command);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        String neighborhoodCode = command.neighborhoodCodePresent()
                ? command.neighborhoodCode().trim() : null;
        if (command.neighborhoodCodePresent()
                && !neighborhoodRepository.existsByCodeAndActiveTrue(
                        neighborhoodCode)) {
            throw new BusinessException(ErrorCode.NEIGHBORHOOD_NOT_FOUND);
        }

        boolean changed = false;
        if (command.nicknamePresent()) {
            changed |= user.changeNickname(command.nickname());
        }
        if (command.neighborhoodCodePresent()) {
            changed |= user.changeNeighborhoodCode(neighborhoodCode);
        }
        if (command.weightKgPresent()) {
            changed |= user.changeWeightKg(command.weightKg());
        }
        if (changed) {
            userRepository.flush();
        }

        return MeResponse.from(user);
    }

    private void validateCommand(MeUpdateCommand command) {
        if (command == null || !command.hasAnyPresentField()
                || (command.nicknamePresent()
                && !isValidNickname(command.nickname()))
                || (command.neighborhoodCodePresent()
                && !isValidNeighborhoodCode(command.neighborhoodCode()))
                || (command.weightKgPresent()
                && !isValidWeightKg(command.weightKg()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean isValidNickname(String nickname) {
        if (nickname == null) {
            return false;
        }
        String trimmedNickname = nickname.trim();
        return !trimmedNickname.isBlank()
                && trimmedNickname.length() >= 2
                && trimmedNickname.length() <= 20;
    }

    private boolean isValidNeighborhoodCode(String neighborhoodCode) {
        if (neighborhoodCode == null) {
            return false;
        }
        String trimmedNeighborhoodCode = neighborhoodCode.trim();
        return !trimmedNeighborhoodCode.isBlank()
                && trimmedNeighborhoodCode.length() <= 20;
    }

    private boolean isValidWeightKg(BigDecimal weightKg) {
        return weightKg == null || (weightKg.scale() <= 2
                && weightKg.compareTo(MIN_WEIGHT_KG) >= 0
                && weightKg.compareTo(MAX_WEIGHT_KG) <= 0);
    }
}
