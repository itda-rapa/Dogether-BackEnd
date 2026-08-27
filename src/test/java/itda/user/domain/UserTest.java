package itda.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    @Nested
    @DisplayName("Describe: 내 정보 필드를 변경한다")
    class DescribeProfileMutation {

        @Test
        @DisplayName("It: nickname을 trim해 변경하고 publicTag는 보존한다")
        void changesNicknameWithoutChangingPublicTag() {
            User user = user();

            boolean changed = user.changeNickname("  새이름  ");

            assertThat(changed).isTrue();
            assertThat(user.getNickname()).isEqualTo("새이름");
            assertThat(user.getPublicTag()).isEqualTo("사용자#A7K2");
        }

        @Test
        @DisplayName("It: 동일한 값과 동일한 체중 수치는 no-op으로 판단한다")
        void reportsNoOpForEquivalentProfileValues() {
            User user = user();
            user.changeWeightKg(new java.math.BigDecimal("72.5"));

            assertThat(user.changeNickname("사용자")).isFalse();
            assertThat(user.changeNeighborhoodCode("  4113111500  ")).isFalse();
            assertThat(user.changeWeightKg(new java.math.BigDecimal("72.50")))
                    .isFalse();
            assertThat(user.getPublicTag()).isEqualTo("사용자#A7K2");
        }

        @Test
        @DisplayName("It: 동네와 체중을 직접 변경 또는 null 초기화해도 publicTag를 보존한다")
        void changesNeighborhoodAndWeightWithoutChangingPublicTag() {
            User user = user();

            assertThat(user.changeNeighborhoodCode("  4113510900  ")).isTrue();
            assertThat(user.changeWeightKg(new java.math.BigDecimal("500.00"))).isTrue();
            assertThat(user.changeWeightKg(null)).isTrue();

            assertThat(user.getNeighborhoodCode()).isEqualTo("4113510900");
            assertThat(user.getWeightKg()).isNull();
            assertThat(user.getPublicTag()).isEqualTo("사용자#A7K2");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.99", "500.01", "72.501"})
        @DisplayName("It: 범위 밖 또는 소수 셋째 자리 체중은 직접 변경할 수 없다")
        void rejectsInvalidDirectWeightChange(String weightKg) {
            User user = user();
            user.changeWeightKg(new java.math.BigDecimal("72.50"));

            assertThatThrownBy(() -> user.changeWeightKg(new java.math.BigDecimal(weightKg)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(user.getWeightKg()).isEqualByComparingTo("72.50");
        }
    }

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
