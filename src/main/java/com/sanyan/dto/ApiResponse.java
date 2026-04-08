package com.sanyan.dto;

import lombok.Data;

@Data
public class ApiResponse<T> {
    private boolean success;
    private String errMsg;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setSuccess(true);
        resp.setData(data);
        return resp;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(String errMsg) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setSuccess(false);
        resp.setErrMsg(errMsg);
        return resp;
    }
}
