package itda.common.config;

import itda.common.properties.AdminBootstrapProperties;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(
            AdminBootstrapProperties properties,
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            return;
        }
        validateRequiredProperties();
        if (userRepository.findByEmailIgnoreCase(properties.email()).isPresent()) {
            return;
        }
        if (!neighborhoodRepository.existsByCodeAndActiveTrue(
                properties.neighborhoodCode()
        )) {
            throw new IllegalStateException(
                    "Initial admin neighborhood does not exist or is inactive"
            );
        }

        User admin = User.register(
                properties.email(),
                passwordEncoder.encode(properties.password()),
                properties.nickname(),
                properties.neighborhoodCode()
        );
        admin.changeRole(Role.SUPER_ADMIN);
        userRepository.save(admin);
    }

    private void validateRequiredProperties() {
        if (!StringUtils.hasText(properties.email())
                || !StringUtils.hasText(properties.password())
                || !StringUtils.hasText(properties.nickname())
                || !StringUtils.hasText(properties.neighborhoodCode())) {
            throw new IllegalStateException(
                    "Initial admin is enabled but required environment values are missing"
            );
        }
    }
}
