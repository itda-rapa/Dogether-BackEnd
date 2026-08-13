package itda.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class PagingTest {

    @Test
    void convertsZeroBasedPageNumberWithoutOffset() {
        PageRequest pageable = new Paging(0, 20).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }

    @Test
    void preservesSubsequentPageNumber() {
        PageRequest pageable = new Paging(2, 10).toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
    }
}
