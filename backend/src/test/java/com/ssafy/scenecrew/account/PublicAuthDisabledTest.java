package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import com.ssafy.scenecrew.account.controller.PublicAuthController;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublicAuthDisabledTest {
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    @Test void publicLoginIsDisabledByDefaultAndIssuesNoAuthenticationCookie() throws Exception {
        assertThat(context.getBeansOfType(PublicAuthController.class)).isEmpty();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/login"))
                    .header("Content-Type","application/json").header("X-SceneCrew-Client","WEB")
                    .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().allValues("Set-Cookie")).noneSatisfy(c->assertThat(c).contains("__Secure-sc-"));
        }
    }
    @Test void publicMeIsDisabledByDefault() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.AccountSummaryReader.class)).isEmpty();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            // Default Spring Security may create JSESSIONID; candidate authentication cookies must stay absent.
            assertThat(response.headers().allValues("Set-Cookie")).noneSatisfy(c->assertThat(c).contains("__Secure-sc-"));
        }
    }
    @Test void profilePatchIsNotEnabledWithoutPublicAuthenticationConfiguration() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.controller.AccountProfileController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.AccountProfileService.class)).isEmpty();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me"))
                    .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"displayName\":\"denied\"}"))
                    .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                    .header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }
    @Test void oauthIsAbsentAndNotPublicByDefault() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.oauth.OAuthWebController.class)).isEmpty();
        try(var client=HttpClient.newHttpClient()) {
            for(String path:java.util.List.of("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME",
                    "/api/v1/auth/oauth/GOOGLE/callback?state=synthetic&code=synthetic")) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.headers().allValues("Set-Cookie")).noneSatisfy(c->assertThat(c).contains("__Secure-sc-"));
            }
        }
    }

}
