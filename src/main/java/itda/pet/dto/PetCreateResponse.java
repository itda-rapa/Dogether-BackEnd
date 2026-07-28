package itda.pet.dto;

import itda.pet.service.ActivePetAssignmentStatus;

public record PetCreateResponse(
        PetResponse pet,
        ActivePetAssignmentStatus activeAssignmentStatus
) {
}
