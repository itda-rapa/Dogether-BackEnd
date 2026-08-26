package itda.medicalsupport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import itda.common.exception.BusinessException;
import itda.medicalsupport.domain.MedicalSupportProgram;
import itda.medicalsupport.domain.MedicalSupportRegionScope;
import itda.medicalsupport.domain.MedicalSupportReviewStatus;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.dto.MedicalSupportProgramResponse;
import itda.medicalsupport.repository.MedicalSupportProgramRepository;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;
import itda.neighborhood.domain.Neighborhood;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MedicalSupportQueryServiceTest {

    private final MedicalSupportProgramRepository programs = mock(MedicalSupportProgramRepository.class);
    private final MedicalSupportRevisionRepository revisions = mock(MedicalSupportRevisionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
    private MedicalSupportQueryService service;

    @BeforeEach
    void setUp() {
        service = new MedicalSupportQueryService(programs, revisions, users, neighborhoods);
    }

    @Test
    void usesCanonicalSidoAndSigunguCodesForListVisibility() {
        givenUserNeighborhood(1L, "1111051500", "서울특별시", "종로구");
        MedicalSupportProgram sido = verifiedProgram(10L, MedicalSupportRegionScope.SIDO, "11", "서울특별시", null);
        MedicalSupportProgram sigungu = verifiedProgram(11L, MedicalSupportRegionScope.SIGUNGU, "11110", "서울특별시", "종로구");
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIDO, "11"))
                .willReturn(List.of(sido));
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIGUNGU, "11110"))
                .willReturn(List.of(sigungu));

        List<MedicalSupportProgramResponse> result = service.listForUser(1L);

        assertThat(result).extracting(MedicalSupportProgramResponse::programId).containsExactly(10L, 11L);
        verify(programs).findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIDO, "11");
        verify(programs).findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIGUNGU, "11110");
    }

    @Test
    void sameDisplayNamesWithDifferentCanonicalCodesDoNotLeakPrograms() {
        givenUserNeighborhood(2L, "4113051000", "서울특별시", "종로구");
        MedicalSupportProgram program = verifiedProgram(20L, MedicalSupportRegionScope.SIGUNGU, "11110", "서울특별시", "종로구");
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIDO, "41"))
                .willReturn(List.of());
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIGUNGU, "41130"))
                .willReturn(List.of());

        assertThat(service.listForUser(2L)).isEmpty();
        verify(programs, never()).findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIGUNGU, "11110");
    }

    @Test
    void pendingAndRejectedRevisionsRemainHidden() {
        givenUserNeighborhood(3L, "1111051500", "서울특별시", "종로구");
        MedicalSupportProgram pending = program(30L, MedicalSupportRegionScope.SIDO, "11", MedicalSupportReviewStatus.PENDING_REVIEW);
        MedicalSupportProgram rejected = program(31L, MedicalSupportRegionScope.SIDO, "11", MedicalSupportReviewStatus.REJECTED);
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIDO, "11"))
                .willReturn(List.of(pending, rejected));
        given(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(MedicalSupportRegionScope.SIGUNGU, "11110"))
                .willReturn(List.of());

        assertThat(service.listForUser(3L)).isEmpty();
    }

    @Test
    void detailAppliesTheSameCanonicalVisibilityRule() {
        givenUserNeighborhood(4L, "1114055000", "서울특별시", "중구");
        MedicalSupportProgram program = verifiedProgram(40L, MedicalSupportRegionScope.SIGUNGU, "11110", "서울특별시", "종로구");
        given(programs.findWithCurrentVerifiedRevisionById(40L)).willReturn(Optional.of(program));

        assertThatThrownBy(() -> service.detail(4L, 40L)).isInstanceOf(BusinessException.class);
    }

    private void givenUserNeighborhood(long userId, String code, String sido, String sigungu) {
        User user = mock(User.class);
        Neighborhood neighborhood = mock(Neighborhood.class);
        given(user.getNeighborhoodCode()).willReturn(code);
        given(users.findById(userId)).willReturn(Optional.of(user));
        given(neighborhoods.findByCodeOrThrow(code)).willReturn(neighborhood);
        given(neighborhood.getCode()).willReturn(code);
        given(neighborhood.getSidoName()).willReturn(sido);
        given(neighborhood.getSigunguName()).willReturn(sigungu);
    }

    private MedicalSupportProgram verifiedProgram(long id, MedicalSupportRegionScope scope, String code,
            String sido, String sigungu) {
        MedicalSupportProgram program = program(id, scope, code, MedicalSupportReviewStatus.VERIFIED);
        given(program.getRegionSidoName()).willReturn(sido);
        given(program.getRegionSigunguName()).willReturn(sigungu);
        return program;
    }

    private MedicalSupportProgram program(long id, MedicalSupportRegionScope scope, String code,
            MedicalSupportReviewStatus status) {
        MedicalSupportProgram program = mock(MedicalSupportProgram.class);
        MedicalSupportRevision revision = mock(MedicalSupportRevision.class);
        given(program.getId()).willReturn(id);
        given(program.getRegionScope()).willReturn(scope);
        given(program.getRegionCode()).willReturn(code);
        given(program.getProgramName()).willReturn("의료지원");
        given(program.getProgramYear()).willReturn(2026);
        given(program.getCurrentVerifiedRevision()).willReturn(revision);
        given(revision.getReviewStatus()).willReturn(status);
        return program;
    }
}
