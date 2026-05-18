package com.sanyan.user.web;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SmsSendReq {
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
