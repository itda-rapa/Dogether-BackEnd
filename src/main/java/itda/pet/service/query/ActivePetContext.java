package itda.pet.service.query;

public record ActivePetContext(
        Long petId,
        Long ownerUserId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified
) {
}
