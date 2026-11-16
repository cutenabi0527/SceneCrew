package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import com.ssafy.scenecrew.account.service.EmailRegistrationService;
import com.ssafy.scenecrew.account.controller.PublicRegistrationController;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistrationDisabledTest {
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    @Test void defaultConfigurationDoesNotExposeRegisterOrInstantiateIntake() throws Exception {
        assertThat(context.getBeansOfType(PublicRegistrationController.class)).isEmpty();
        assertThat(context.getBeansOfType(EmailRegistrationService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.controller.PublicVerificationController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.EmailVerificationService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.controller.PublicPasswordResetController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.PasswordResetService.class)).isEmpty();
        for (String path : java.util.List.of("register","verify-email","resend-verification",
                "password-reset/request","password-reset/confirm")) {
        try(var client=HttpClient.newHttpClient()) {
            var result=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/"+path))
                    .header("Content-Type","application/json").header("X-SceneCrew-Client","WEB")
                    .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(403);
            assertThat(result.headers().allValues("Set-Cookie")).noneSatisfy(c->assertThat(c).contains("__Secure-sc-"));
        }
        }
    }
}
