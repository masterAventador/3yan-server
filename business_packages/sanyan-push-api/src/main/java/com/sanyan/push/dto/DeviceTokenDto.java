package com.sanyan.push.dto;

public record DeviceTokenDto(Long userId, String platform, String vendor, String token) {}
