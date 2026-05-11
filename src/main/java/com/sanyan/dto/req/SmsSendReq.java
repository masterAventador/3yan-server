package com.sanyan.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsSendReq {
    @NotBlank private String phone;
}
