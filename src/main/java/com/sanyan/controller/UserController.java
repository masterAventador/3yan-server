package com.sanyan.controller;

import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.UserProfileData;
import com.sanyan.dto.req.ProfileUpdateReq;
import com.sanyan.entity.User;
import com.sanyan.repository.UserRepository;
import com.sanyan.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    @GetMapping("/profile")
    public ApiResponse<UserProfileData> getProfile(HttpServletRequest request) {
        Long userId = getUserId(request);
        return userRepository.findById(userId)
                .map(u -> ApiResponse.ok(toData(u)))
                .orElse(ApiResponse.fail("用户不存在"));
    }

    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(HttpServletRequest request, @RequestBody ProfileUpdateReq req) {
        Long userId = getUserId(request);
        User user = userRepository.findById(userId).orElseThrow();
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar());
        userRepository.save(user);
        return ApiResponse.ok();
    }

    private Long getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return jwtUtil.parseUserId(token);
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
