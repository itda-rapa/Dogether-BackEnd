package itda.medicalsupport.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.medicalsupport.domain.MedicalSupportProgram;
import itda.medicalsupport.domain.MedicalSupportRegionScope;
import itda.medicalsupport.dto.MedicalSupportProgramResponse;
import itda.medicalsupport.repository.MedicalSupportProgramRepository;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;
import itda.neighborhood.domain.Neighborhood;
import itda.neighborhood.domain.NeighborhoodCodeHierarchy;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MedicalSupportQueryService {

    private final MedicalSupportProgramRepository programs;
    private final MedicalSupportRevisionRepository revisions;
    private final UserRepository users;
    private final NeighborhoodRepository neighborhoods;

    @Transactional(readOnly = true)
    public List<MedicalSupportProgramResponse> listForUser(long userId) {
        NeighborhoodCodeHierarchy hierarchy = hierarchyFor(userId);
        List<MedicalSupportProgram> result = new ArrayList<>(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(
                MedicalSupportRegionScope.SIDO, hierarchy.sidoCode()));
        result.addAll(programs.findByRegionScopeAndRegionCodeOrderByIdDesc(
                MedicalSupportRegionScope.SIGUNGU, hierarchy.sigunguCode()));
        return result.stream().filter(this::hasVerifiedRevision).map(MedicalSupportProgramResponse::list).toList();
    }

    @Transactional(readOnly = true)
    public MedicalSupportProgramResponse detail(long userId, long id) {
        NeighborhoodCodeHierarchy hierarchy = hierarchyFor(userId);
        MedicalSupportProgram program = programs.findWithCurrentVerifiedRevisionById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!hasVerifiedRevision(program) || !visible(program, hierarchy)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return MedicalSupportProgramResponse.detail(program,
                revisions.findWithHospitalsById(program.getCurrentVerifiedRevision().getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    private NeighborhoodCodeHierarchy hierarchyFor(long userId) {
        Neighborhood neighborhood = users.findById(userId)
                .map(user -> neighborhoods.findByCodeOrThrow(user.getNeighborhoodCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return NeighborhoodCodeHierarchy.from(neighborhood);
    }

    private boolean visible(MedicalSupportProgram program, NeighborhoodCodeHierarchy hierarchy) {
        return switch (program.getRegionScope()) {
            case SIDO -> program.getRegionCode().equals(hierarchy.sidoCode());
            case SIGUNGU -> program.getRegionCode().equals(hierarchy.sigunguCode());
        };
    }

    private boolean hasVerifiedRevision(MedicalSupportProgram program) {
        return program.getCurrentVerifiedRevision() != null
                && program.getCurrentVerifiedRevision().getReviewStatus()
                == itda.medicalsupport.domain.MedicalSupportReviewStatus.VERIFIED;
    }
}
