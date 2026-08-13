package itda.meetingcard.dto.response;

import itda.pet.service.query.PetDisplaySummary;

public record OpenChatDraftParticipantResponse(
        Long petId,
        String nickname,
        String profileUrl
) {
    public static OpenChatDraftParticipantResponse from(PetDisplaySummary pet) {
        return new OpenChatDraftParticipantResponse(
                pet.petId(), pet.nickname(), pet.profileUrl());
    }
}
