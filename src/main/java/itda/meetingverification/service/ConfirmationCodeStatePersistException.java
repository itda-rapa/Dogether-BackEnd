package itda.meetingverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;

/** Expiry/failed-attempt state must commit with its API error; other errors roll back. */
public final class ConfirmationCodeStatePersistException extends BusinessException {

    public ConfirmationCodeStatePersistException(ErrorCode errorCode) {
        super(errorCode);
    }
}
