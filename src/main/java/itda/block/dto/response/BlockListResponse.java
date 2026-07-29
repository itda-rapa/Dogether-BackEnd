package itda.block.dto.response;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record BlockListResponse(
        List<BlockResponse> items,
        CursorPage page
) {}