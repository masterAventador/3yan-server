package com.sanyan.user.web;

import lombok.Data;

@Data
public class LoginData {
    private Long userId;
    private String token;
    private String nickname;
    private String avatar;
}
