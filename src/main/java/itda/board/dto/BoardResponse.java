package itda.board.dto;

import itda.board.domain.Board;

public record BoardResponse(
        Long boardId,
        String name,
        long version
) {

    public static BoardResponse from(Board board) {
        return new BoardResponse(
                board.getId(),
                board.getName(),
                board.getVersion()
        );
    }
}
