package com.sanyan.user.web;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsSendReq {
    @NotBlank private String phone;
}
