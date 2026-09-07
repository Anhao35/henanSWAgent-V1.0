package cn.edu.ha.secagent.auth;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.VerificationCode;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.repository.VerificationCodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationCodeServiceTest {
    @Test
    void sendsSixDigitEmailCodeAndConsumesItOnce() {
        var codes = mock(VerificationCodeRepository.class);
        var users = mock(UserRepository.class);
        var mail = mock(JavaMailSender.class);
        var saved = new AtomicReference<VerificationCode>();
        when(codes.findFirstByPurposeAndChannelAndSubjectOrderByCreatedAtDesc(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(codes.saveAndFlush(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(codes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new VerificationCodeService(codes, users, mail, RestClient.builder(), properties());
        var result = service.send(new AuthDtos.SendVerificationCodeRequest("REGISTER", "EMAIL", "analyst@example.com"));

        assertThat(result.devCode()).matches("\\d{6}");
        assertThat(result.maskedTarget()).isEqualTo("an***@example.com");
        verify(mail).send(any(org.springframework.mail.SimpleMailMessage.class));

        service.verifyRegistration("EMAIL", "analyst@example.com", result.devCode());
        assertThat(saved.get().getConsumedAt()).isNotNull();
        assertThatThrownBy(() -> service.verifyRegistration("EMAIL", "analyst@example.com", result.devCode()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void phoneCodeIsFourDigitsInLocalDevelopmentMode() {
        var codes = mock(VerificationCodeRepository.class);
        var users = mock(UserRepository.class);
        when(codes.findFirstByPurposeAndChannelAndSubjectOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(codes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new VerificationCodeService(codes, users, mock(JavaMailSender.class), RestClient.builder(), properties());

        var result = service.send(new AuthDtos.SendVerificationCodeRequest("REGISTER", "PHONE", "13800138000"));

        assertThat(result.devCode()).matches("\\d{4}");
        assertThat(result.maskedTarget()).isEqualTo("138****8000");
    }

    private AppProperties properties() {
        return new AppProperties(null, null, null, null,
                new AppProperties.Verification(10, 60, 5, true, "unit-test-secret",
                        new AppProperties.Verification.Sms("", "")),
                null, null, null, null);
    }
}
