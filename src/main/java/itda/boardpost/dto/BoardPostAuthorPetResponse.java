package itda.boardpost.dto;
import itda.pet.service.query.PetDisplaySummary;
public record BoardPostAuthorPetResponse(Long petId, String publicTag, String nickname, String profileUrl, boolean verified) {
    public static BoardPostAuthorPetResponse from(PetDisplaySummary pet) { return new BoardPostAuthorPetResponse(pet.petId(), pet.publicTag(), pet.nickname(), pet.profileUrl(), pet.verified()); }
}
