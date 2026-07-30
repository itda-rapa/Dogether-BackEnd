package itda.friend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FriendshipTest {

    @Test
    void sortsPetPair() {
        Friendship friendship = Friendship.create(20L, 10L);

        assertThat(friendship.getPetLowId()).isEqualTo(10L);
        assertThat(friendship.getPetHighId()).isEqualTo(20L);
    }

    @Test
    void rejectsNullPet() {
        assertThatThrownBy(() -> Friendship.create(null, 2L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Friendship.create(1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsSamePet() {
        assertThatThrownBy(() -> Friendship.create(1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
