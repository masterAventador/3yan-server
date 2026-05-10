package com.sanyan.controller;

import com.sanyan.auth.LoginUser;
import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.UserProfileData;
import com.sanyan.dto.req.ProfileUpdateReq;
import com.sanyan.entity.User;
import com.sanyan.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/profile")
    public ApiResponse<UserProfileData> getProfile(@LoginUser Long userId) {
        return userRepository.findById(userId)
                .map(u -> ApiResponse.ok(toData(u)))
                .orElse(ApiResponse.fail("用户不存在"));
    }

    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(@LoginUser Long userId, @RequestBody ProfileUpdateReq req) {
        User user = userRepository.findById(userId).orElseThrow();
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar());
        userRepository.save(user);
        return ApiResponse.ok();
    }

    private UserProfileData toData(User u) {
        UserProfileData d = new UserProfileData();
        d.setId(u.getId());
        d.setPhone(u.getPhone());
        d.setNickname(u.getNickname());
        d.setAvatar(u.getAvatar());
        return d;
    }
}
