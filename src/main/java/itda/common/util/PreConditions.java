package itda.common.util;

import itda.common.exception.BusinessException;
import itda.common.constants.ErrorCode;

public final class PreConditions {
    public static void validate(boolean expression, ErrorCode errorCode){
        if (!expression) throw new BusinessException(errorCode);
    }
}