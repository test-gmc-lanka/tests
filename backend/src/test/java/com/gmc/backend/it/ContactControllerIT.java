package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.User;
import com.gmc.backend.repository.NotificationRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class ContactControllerIT extends IntegrationTestBase {

    @Autowired
    private NotificationRepository notificationRepository;

    private Map<String, Object> message(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Nimal Perera");
        body.put("email", email);
        body.put("phone", "077 123 4567");
        body.put("subject", "Custom dining table");
        body.put("message", "Can you make a 6-seater teak table?");
        return body;
    }

    private static String uniqueEmail() {
        return "visitor-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void send_anonymous_notifiesAdminsInAppAndByEmail() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(post("/api/contact")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(message(uniqueEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Thanks! Your message has been sent. We'll reply by email."));

        assertThat(notificationRepository.countByUserAndIsReadFalse(admin)).isEqualTo(1);
        verify(emailService)
                .send(eq(admin.getEmail()), contains("Custom dining table"), contains("6-seater teak table"));
    }

    @Test
    void send_withInvalidEmailShortMessageAndBadPhone_returns400() throws Exception {
        Map<String, Object> body = message("not-an-email");
        body.put("message", "Hi");
        body.put("phone", "abc");

        mockMvc.perform(post("/api/contact")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(emailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void send_withoutPhone_isAccepted() throws Exception {
        Map<String, Object> body = message(uniqueEmail());
        body.put("phone", "");

        mockMvc.perform(post("/api/contact")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk());
    }

    @Test
    void send_fourthMessageFromSameEmail_returns429() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/contact")
                            .with(uniqueClientIp())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(message(email))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/contact")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(message(email.toUpperCase()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void send_sixthMessageFromSameIp_returns429() throws Exception {
        String ip = "10.251." + (int) (Math.random() * 250) + ".7";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/contact")
                            .with(fromIp(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(message(uniqueEmail()))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/contact")
                        .with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(message(uniqueEmail()))))
                .andExpect(status().isTooManyRequests());
    }
}
