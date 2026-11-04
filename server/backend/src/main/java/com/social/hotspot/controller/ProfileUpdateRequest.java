package com.social.hotspot.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProfileUpdateRequest(
        @NotBlank(message = "请填写昵称") @Size(max = 100) String nickname,
        @Size(max = 32) @Pattern(regexp = "^$|\\+?[0-9][0-9 ()-]{5,31}", message = "手机号格式不正确") String phone,
        @Size(max = 120) @Email(message = "邮箱格式不正确") String email,
        @Size(max = 300) String bio) {
    public Map<String, Object> fields() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nickname", nickname.trim());
        result.put("phone", phone == null ? null : phone.trim());
        result.put("email", email == null ? null : email.trim());
        result.put("bio", bio == null ? null : bio.trim());
        return result;
    }
}
