package itda.boardpost.dto;
public record BoardPostUpdateRequest(boolean titlePresent, String title, boolean contentPresent, String content, long version) {}
