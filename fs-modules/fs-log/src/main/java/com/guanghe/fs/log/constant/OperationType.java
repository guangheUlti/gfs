package com.guanghe.fs.log.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 操作日志类型。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OperationType {

    public static final String UPLOAD = "UPLOAD";
    public static final String DOWNLOAD = "DOWNLOAD";
    public static final String CREATE_FOLDER = "CREATE_FOLDER";
    public static final String CREATE_TEXT = "CREATE_TEXT";
    public static final String EDIT_TEXT = "EDIT_TEXT";
    public static final String COPY = "COPY";
    public static final String MOVE = "MOVE";
    public static final String RENAME = "RENAME";
    public static final String DELETE = "DELETE";
    public static final String RESTORE = "RESTORE";
    public static final String PERMANENT_DELETE = "PERMANENT_DELETE";
    public static final String CLEAR_RECYCLE = "CLEAR_RECYCLE";
    public static final String CREATE_SHARE = "CREATE_SHARE";
    public static final String CANCEL_SHARE = "CANCEL_SHARE";
    public static final String CREATE_COLLECTION = "CREATE_COLLECTION";
    public static final String UPDATE_COLLECTION = "UPDATE_COLLECTION";
    public static final String DELETE_COLLECTION = "DELETE_COLLECTION";
    public static final String COLLECTION_UPLOAD = "COLLECTION_UPLOAD";
    public static final String ADD_STORAGE = "ADD_STORAGE";
    public static final String UPDATE_STORAGE = "UPDATE_STORAGE";
    public static final String SWITCH_STORAGE = "SWITCH_STORAGE";
    public static final String DELETE_STORAGE = "DELETE_STORAGE";
    public static final String CREATE_USER = "CREATE_USER";
    public static final String APPROVE_USER = "APPROVE_USER";
    public static final String REJECT_USER = "REJECT_USER";
    public static final String KICKOUT_SESSION = "KICKOUT_SESSION";
}
