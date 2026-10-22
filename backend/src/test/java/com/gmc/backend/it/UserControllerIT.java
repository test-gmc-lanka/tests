package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Notification;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.NotificationRepository;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class UserControllerIT extends IntegrationTestBase {

    @Autowired
    private NotificationRepository notificationRepository;

    private Notification notify(User user, String message) {
        return notificationRepository.save(Notification.builder()
                .message(message)
                .type("ORDER")
                .link("/orders/1")
                .user(user)
                .build());
    }

    private long unreadCount(User user) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/users/me/notifications/unread-count").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("count")
                .asLong();
    }

    private Cookie loginCookie(User user, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.getEmail(), "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("refreshToken");
    }

    // ── Profile ─────────────────────────────────────────────

    @Test
    void getProfile_returnsCallersDetailsWithoutPassword() throws Exception {
        User user = createCustomer();

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void getProfile_worksForEveryRole() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(technician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TECHNICIAN"));
    }

    @Test
    void getProfile_anonymous_returns403() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isForbidden());
    }

    @Test
    void getProfile_withTamperedToken_returns401() throws Exception {
        User user = createCustomer();

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(user) + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_changesProvidedFieldsOnly() throws Exception {
        User user = createCustomer();

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Kamal Silva", "address", "5 Lake Road, Kandy"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kamal Silva"))
                .andExpect(jsonPath("$.address").value("5 Lake Road, Kandy"))
                .andExpect(jsonPath("$.phone").value("0771234567"))
                .andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    @Test
    void updateProfile_withBlankName_keepsExistingName() throws Exception {
        User user = createCustomer();

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(user.getName()));
    }

    @Test
    void updateProfile_withTooLongPhone_returns400() throws Exception {
        User user = createCustomer();

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", "0".repeat(31)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    // ── Avatar ──────────────────────────────────────────────

    @Test
    void uploadAvatar_withPng_setsProfileImageUrl() throws Exception {
        User user = createCustomer();
        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/users/me/avatar").file(file).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl", startsWith("/uploads/avatars/")));

        assertThat(userRepository.findById(user.getUserId()).orElseThrow().getProfileImageUrl())
                .startsWith("/uploads/avatars/");
    }

    @Test
    void uploadAvatar_withDisallowedType_returns400() throws Exception {
        User user = createCustomer();
        MockMultipartFile file = new MockMultipartFile("file", "me.svg", "image/svg+xml", new byte[] {1});

        mockMvc.perform(multipart("/api/users/me/avatar").file(file).header("Authorization", bearer(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILE"));
    }

    @Test
    void uploadAvatar_withEmptyFile_returns400() throws Exception {
        User user = createCustomer();
        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/api/users/me/avatar").file(file).header("Authorization", bearer(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeAvatar_clearsProfileImageUrl() throws Exception {
        User user = createCustomer();
        user.setProfileImageUrl("/uploads/avatars/old.png");
        userRepository.save(user);

        mockMvc.perform(delete("/api/users/me/avatar").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").doesNotExist());
    }

    @Test
    void removeAvatar_anonymous_returns403() throws Exception {
        mockMvc.perform(delete("/api/users/me/avatar")).andExpect(status().isForbidden());
    }

    // ── Password ────────────────────────────────────────────

    @Test
    void changePassword_withCorrectCurrent_revokesOldSessionsAndStartsNewOne() throws Exception {
        User user = createCustomer();
        Cookie oldSession = loginCookie(user, PASSWORD);

        MvcResult result = mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "Changed@789"))))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie newSession = result.getResponse().getCookie("refreshToken");
        assertThat(newSession).isNotNull();
        mockMvc.perform(post("/api/auth/refresh").cookie(oldSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(newSession)).andExpect(status().isOk());
        loginCookie(user, "Changed@789");
    }

    @Test
    void changePassword_withWrongCurrent_returns422AndKeepsPassword() throws Exception {
        User user = createCustomer();

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "NotMyPassword1", "newPassword", "Changed@789"))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(passwordEncoder.matches(
                        PASSWORD,
                        userRepository.findById(user.getUserId()).orElseThrow().getPassword()))
                .isTrue();
    }

    @Test
    void changePassword_withShortNewPassword_returns400() throws Exception {
        User user = createCustomer();

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest());
    }

    // ── Notifications ───────────────────────────────────────

    @Test
    void getNotifications_returnsOnlyCallersNotifications() throws Exception {
        User user = createCustomer();
        notify(user, "Your order has shipped");
        notify(createCustomer(), "Someone else's order");

        mockMvc.perform(get("/api/users/me/notifications").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message").value("Your order has shipped"))
                .andExpect(jsonPath("$[0].type").value("ORDER"))
                .andExpect(jsonPath("$[0].link").value("/orders/1"))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void getUnreadCount_countsUnreadNotifications() throws Exception {
        User user = createCustomer();
        notify(user, "One");
        notify(user, "Two");

        assertThat(unreadCount(user)).isEqualTo(2);
    }

    @Test
    void markRead_ownNotification_decrementsUnreadCount() throws Exception {
        User user = createCustomer();
        Notification first = notify(user, "One");
        notify(user, "Two");

        mockMvc.perform(post("/api/users/me/notifications/{id}/read", first.getNotificationId())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(unreadCount(user)).isEqualTo(1);
    }

    @Test
    void markRead_someoneElsesNotification_leavesItUnread() throws Exception {
        User owner = createCustomer();
        Notification notification = notify(owner, "Private");
        User stranger = createCustomer();

        mockMvc.perform(post("/api/users/me/notifications/{id}/read", notification.getNotificationId())
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNoContent());

        assertThat(unreadCount(owner)).isEqualTo(1);
    }

    @Test
    void markAllRead_clearsUnreadCount() throws Exception {
        User user = createCustomer();
        notify(user, "One");
        notify(user, "Two");
        User other = createCustomer();
        notify(other, "Other");

        mockMvc.perform(post("/api/users/me/notifications/mark-read").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(unreadCount(user)).isZero();
        assertThat(unreadCount(other)).isEqualTo(1);
    }

    @Test
    void notifications_anonymous_returns403() throws Exception {
        mockMvc.perform(get("/api/users/me/notifications")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/me/notifications/mark-read")).andExpect(status().isForbidden());
    }
}
