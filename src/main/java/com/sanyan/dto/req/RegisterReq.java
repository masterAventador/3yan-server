package com.sanyan.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterReq {
    @NotBlank private String phone;
    @NotBlank private String code;
    @NotBlank private String password;
    private String nickname;
}
