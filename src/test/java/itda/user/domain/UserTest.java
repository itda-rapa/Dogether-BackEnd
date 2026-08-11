package itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserTest {

    @Nested
    @DisplayName("Describe: Active Pet을 관리한다")
    class DescribeActivePetManagement {

        @Nested
        @DisplayName("Context: 아직 Active Pet이 없을 때")
        class ContextWithoutActivePet {

            @Test
            @DisplayName("It: 어떤 Pet도 Active Pet으로 판단하지 않는다")
            void itHasNoActivePet() {
                User user = user();

                assertThat(user.getActivePetId()).isNull();
                assertThat(user.hasActivePet()).isFalse();
                assertThat(user.isActivePet(1L)).isFalse();
                assertThat(user.isActivePet(null)).isFalse();
            }
        }

        @Nested
        @DisplayName("Context: 새로운 Pet을 선택할 때")
        class ContextSelectingNewPet {

            @Test
            @DisplayName("It: Active Pet ID를 변경한다")
            void itChangesActivePetId() {
                User user = user();

                user.selectActivePet(1L);

                assertThat(user.getActivePetId()).isEqualTo(1L);
                assertThat(user.hasActivePet()).isTrue();
                assertThat(user.isActivePet(1L)).isTrue();
                assertThat(user.isActivePet(2L)).isFalse();
                assertThat(user.isActivePet(null)).isFalse();
            }
        }

        @Nested
        @DisplayName("Context: 같은 Pet을 다시 선택할 때")
        class ContextReselectingSamePet {

            @Test
            @DisplayName("It: 기존 Active Pet ID를 유지한다")
            void itKeepsActivePetId() {
                User user = user();
                user.selectActivePet(1L);

                user.selectActivePet(1L);

                assertThat(user.getActivePetId()).isEqualTo(1L);
            }
        }

        @Nested
        @DisplayName("Context: null Pet ID를 선택할 때")
        class ContextSelectingNullPet {

            @Test
            @DisplayName("It: 예외를 발생시키고 기존 Active Pet ID를 유지한다")
            void itRejectsAndKeepsActivePetId() {
                User user = user();
                user.selectActivePet(1L);

                assertThatThrownBy(() -> user.selectActivePet(null))
                        .isInstanceOf(NullPointerException.class)
                        .hasMessage("petId는 null일 수 없습니다.");
                assertThat(user.getActivePetId()).isEqualTo(1L);
            }
        }
    }

    private User user() {
        return User.register(
                "user@example.com",
                "encoded",
                "사용자",
                "사용자#A7K2",
                "4113111500"
        );
    }
}
