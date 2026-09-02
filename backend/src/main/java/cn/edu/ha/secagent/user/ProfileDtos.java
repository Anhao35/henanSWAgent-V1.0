package cn.edu.ha.secagent.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record UpdateProfileRequest(
            @Size(min = 1, max = 64) String displayName,
            @Email @Size(max = 128) String email,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @Pattern(regexp = "^$|MALE|FEMALE|OTHER|UNDISCLOSED", message = "性别取值不正确") String gender,
            @Past LocalDate birthDate,
            @Size(max = 64) String education,
            @Size(max = 128) String jobTitle,
            @Size(max = 500) String bio
    ) {}
}

