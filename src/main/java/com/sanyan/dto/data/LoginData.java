package com.sanyan.dto.data;

import lombok.Data;

@Data
public class LoginData {
    private Long userId;
    private String token;
    private String nickname;
    private String avatar;
}
