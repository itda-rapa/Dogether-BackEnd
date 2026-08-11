package itda.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import itda.user.domain.User;
import org.junit.jupiter.api.Test;

class CurrentUserTest {

    @Test
    void principalDoesNotRetainPasswordHash() {
        User user = User.register(
                "user@example.com",
                "encoded-password-hash",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );

        CurrentUser currentUser = CurrentUser.from(user);

        assertThat(currentUser.getPassword()).isNull();
        assertThat(currentUser.toString())
                .doesNotContain("encoded-password-hash");
    }
}
