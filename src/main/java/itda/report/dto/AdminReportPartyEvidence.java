package itda.report.dto;

import itda.pet.domain.Pet;

public record AdminReportPartyEvidence(
        Long userId,
        String userPublicTag,
        String userNickname,
        Long petId,
        String petPublicTag,
        String petNickname
) {

    public static AdminReportPartyEvidence from(Pet pet) {
        return new AdminReportPartyEvidence(
                pet.getOwner().getId(),
                pet.getOwner().getPublicTag(),
                pet.getOwner().getNickname(),
                pet.getId(),
                pet.getPublicTag(),
                pet.getNickname()
        );
    }
}
