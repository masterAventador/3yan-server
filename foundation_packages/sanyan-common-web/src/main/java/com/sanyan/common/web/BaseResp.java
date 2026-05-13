package com.sanyan.common.web;

import lombok.Data;

@Data
public class BaseResp<T> {
    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_MESSAGE = "ok";

    private boolean success;
    private int code;
    private String message;
    private T data;

    public static <T> BaseResp<T> success(T data) {
        BaseResp<T> r = new BaseResp<>();
        r.success = true;
        r.code = SUCCESS_CODE;
        r.message = SUCCESS_MESSAGE;
        r.data = data;
        return r;
    }

    public static <T> BaseResp<T> failed(int code, String message) {
        BaseResp<T> r = new BaseResp<>();
        r.success = false;
        r.code = code;
        r.message = message;
        return r;
    }
}
