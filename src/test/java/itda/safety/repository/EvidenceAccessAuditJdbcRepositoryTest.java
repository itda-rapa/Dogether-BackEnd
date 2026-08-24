package itda.safety.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import itda.safety.domain.EvidenceAccessResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EvidenceAccessAuditJdbcRepositoryTest {

    @Test
    void rejectsFailureCodeLongerThanDatabaseColumnBeforeInsert() {
        EvidenceAccessAuditJdbcRepository repository =
                new EvidenceAccessAuditJdbcRepository(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> repository.append(
                1L, 2L, "EVIDENCE_PAGE", 1L, "review",
                EvidenceAccessResult.FAILED, "A".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
    }
}
