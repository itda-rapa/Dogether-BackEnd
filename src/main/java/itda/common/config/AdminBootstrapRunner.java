package itda.common.config;

import itda.common.properties.AdminBootstrapProperties;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.user.service.PublicTagGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

@Configuration
@RequiredArgsConstructor
public class AdminBootstrapRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    @Transactional
    CommandLineRunner init(UserRepository userRepository) {
        return args -> {
            if (userRepository.count() < 1) { // 중복 방지

                userRepository.save(
                        User.register(
                                "nodongdong@naver.com",
                                passwordEncoder.encode("12345678910"),
                                "노동동",
                                "태그",
                                "4234122132"
                        )
                );

            }
        };
    }
}
