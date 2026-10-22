package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.User;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthControllerIT extends IntegrationTestBase {

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andReturn();
    }

    private Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refreshToken");
        assertThat(cookie).as("refreshToken cookie").isNotNull();
        return cookie;
    }

    @Test
    void register_withValidDetails_returns201AndCustomerToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                Map.of("name", "Nimal Perera", "email", "nimal.it@example.com", "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        User saved = userRepository.findByEmail("nimal.it@example.com").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo(PASSWORD).startsWith("$2");
    }

    @Test
    void register_withDuplicateEmail_isRejected() throws Exception {
        User existing = createCustomer();

        mockMvc.perform(post("/api/auth/register")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                Map.of("name", "Someone Else", "email", existing.getEmail(), "password", PASSWORD))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void register_withShortPasswordAndBadEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nimal", "email", "not-an-email", "password", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withCorrectPassword_returnsTokenAndHttpOnlyRefreshCookie() throws Exception {
        User user = createCustomer();

        MvcResult result = login(user.getEmail(), PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("accessToken");
        assertThat(refreshCookie(result).isHttpOnly()).isTrue();
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        User user = createCustomer();

        assertThat(login(user.getEmail(), "WrongPassword1").getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void login_withMissingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_afterTenFailedAttempts_returns429() throws Exception {
        User user = createCustomer();
        String body = json(Map.of("email", user.getEmail(), "password", "WrongPassword1"));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("10.250.0.1");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("10.250.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void refresh_withValidCookie_returnsNewAccessToken() throws Exception {
        User user = createCustomer();
        Cookie cookie = refreshCookie(login(user.getEmail(), PASSWORD));

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void refresh_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_withUnknownToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", "not-a-real-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_invalidatesRefreshToken() throws Exception {
        User user = createCustomer();
        Cookie cookie = refreshCookie(login(user.getEmail(), PASSWORD));

        mockMvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withoutCookie_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void forgotPassword_forKnownEmail_sendsResetLinkWithGenericReply() throws Exception {
        User user = createCustomer();

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("If an account exists for that email, a password reset link has been sent."));

        verify(emailService).send(eq(user.getEmail()), anyString(), anyString());
    }

    @Test
    void forgotPassword_forUnknownEmail_givesSameReplyAndSendsNothing() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nobody-here@example.com"))))
                .andExpect(status().isOk());

        verify(emailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resetPassword_withEmailedToken_changesPasswordOnce() throws Exception {
        User user = createCustomer();
        mockMvc.perform(post("/api/auth/forgot-password")
                .with(uniqueClientIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", user.getEmail()))));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(user.getEmail()), anyString(), body.capture());
        String token = body.getValue().replaceAll("(?s).*token=(\\S+).*", "$1");
        String reset = json(Map.of("token", token, "newPassword", "BrandNew@456"));

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reset))
                .andExpect(status().isNoContent());

        assertThat(login(user.getEmail(), "BrandNew@456").getResponse().getStatus())
                .isEqualTo(200);
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reset))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void resetPassword_withInvalidToken_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", "bogus", "newPassword", "BrandNew@456"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void protectedEndpoint_withoutToken_isDenied() throws Exception {
        mockMvc.perform(get("/api/cart")).andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_withWrongRole_returns403() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }
}
