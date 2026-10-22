package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Appointment;
import com.gmc.backend.model.AppointmentStatus;
import com.gmc.backend.model.ProductCategory;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.AppointmentRepository;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

class AppointmentControllerIT extends IntegrationTestBase {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    private static String futureDate(int days) {
        return LocalDateTime.now()
                .plusDays(days)
                .truncatedTo(ChronoUnit.MINUTES)
                .toString();
    }

    private Appointment saveAppointment(User owner, AppointmentStatus status) {
        return appointmentRepository.save(Appointment.builder()
                .date(LocalDateTime.now().plusDays(5))
                .message("Need advice on a teak dining table")
                .status(status)
                .user(owner)
                .build());
    }

    private static MockMultipartFile image(String filename) {
        return new MockMultipartFile("files", filename, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
    }

    private static Map<String, Object> bookingBody(String date, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date);
        body.put("message", message);
        return body;
    }

    @Test
    void uploadImages_withPngFiles_returns201AndAppointmentUrls() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/appointments/images")
                        .file(image("table.png"))
                        .file(image("chair.png"))
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]").value(matchesPattern("^/uploads/appointments/[A-Za-z0-9-]+\\.png$")));
    }

    @Test
    void uploadImages_withDisallowedExtension_returns400() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/appointments/images")
                        .file(new MockMultipartFile("files", "virus.exe", "application/octet-stream", PNG_BYTES))
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILE"));
    }

    @Test
    void uploadImages_withMoreThanSixFiles_returns422() throws Exception {
        User customer = createCustomer();
        MockMultipartHttpServletRequestBuilder request = multipart("/api/appointments/images");
        for (int i = 0; i < 7; i++) {
            request.file(image("photo" + i + ".png"));
        }

        mockMvc.perform(request.header("Authorization", bearer(customer))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void book_withUploadedImagesAndCategory_returns201Pending() throws Exception {
        User customer = createCustomer();
        ProductCategory category = categoryRepository.save(
                ProductCategory.builder().name("Dining IT").build());
        String uploaded = mockMvc.perform(multipart("/api/appointments/images")
                        .file(image("table.png"))
                        .header("Authorization", bearer(customer)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageUrl = JsonPath.read(uploaded, "$[0]");
        Map<String, Object> body = bookingBody(futureDate(7), "Please measure my dining room");
        body.put("categoryId", category.getCategoryId());
        body.put("imageUrls", List.of(imageUrl));

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.userId").value(customer.getUserId()))
                .andExpect(jsonPath("$.categoryName").value("Dining IT"))
                .andExpect(jsonPath("$.imageUrls[0]").value(imageUrl));

        assertThat(appointmentRepository.findByUserUserId(customer.getUserId())).hasSize(1);
    }

    @Test
    void book_withPastDateOrBlankMessage_returns400() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                bookingBody(LocalDateTime.now().minusDays(1).toString(), "Too late"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bookingBody(futureDate(3), " "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void book_withForeignImageUrl_returns422() throws Exception {
        User customer = createCustomer();
        Map<String, Object> body = bookingBody(futureDate(3), "See attached");
        body.put("imageUrls", List.of("https://evil.example.com/x.png"));

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void book_withUnknownCategory_returns422() throws Exception {
        User customer = createCustomer();
        Map<String, Object> body = bookingBody(futureDate(3), "About a product line");
        body.put("categoryId", 999_999L);

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getMyAppointments_returnsOnlyCallersAppointments() throws Exception {
        User customer = createCustomer();
        User other = createCustomer();
        Appointment mine = saveAppointment(customer, AppointmentStatus.PENDING);
        saveAppointment(other, AppointmentStatus.PENDING);

        mockMvc.perform(get("/api/appointments").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appointmentId").value(mine.getAppointmentId()));
    }

    @Test
    void reschedule_ownApprovedAppointment_movesBackToPending() throws Exception {
        User customer = createCustomer();
        Appointment appointment = saveAppointment(customer, AppointmentStatus.APPROVED);
        String newDate = futureDate(14);

        mockMvc.perform(put("/api/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("newDate", newDate))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(appointmentRepository
                        .findById(appointment.getAppointmentId())
                        .orElseThrow()
                        .getDate())
                .isEqualTo(LocalDateTime.parse(newDate));
    }

    @Test
    void reschedule_someoneElsesAppointment_returns404() throws Exception {
        User owner = createCustomer();
        User intruder = createCustomer();
        Appointment appointment = saveAppointment(owner, AppointmentStatus.PENDING);

        mockMvc.perform(put("/api/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("newDate", futureDate(9)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void reschedule_cancelledAppointment_returns422() throws Exception {
        User customer = createCustomer();
        Appointment appointment = saveAppointment(customer, AppointmentStatus.CANCELLED);

        mockMvc.perform(put("/api/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("newDate", futureDate(9)))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reschedule_withPastDate_returns400() throws Exception {
        User customer = createCustomer();
        Appointment appointment = saveAppointment(customer, AppointmentStatus.PENDING);

        mockMvc.perform(put("/api/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "newDate", LocalDateTime.now().minusHours(2).toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancel_ownPendingAppointment_returnsCancelled() throws Exception {
        User customer = createCustomer();
        Appointment appointment = saveAppointment(customer, AppointmentStatus.PENDING);

        mockMvc.perform(delete("/api/appointments/{id}", appointment.getAppointmentId())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(appointmentRepository
                        .findById(appointment.getAppointmentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancel_someoneElsesAppointment_returns404AndLeavesItUnchanged() throws Exception {
        User owner = createCustomer();
        User intruder = createCustomer();
        Appointment appointment = saveAppointment(owner, AppointmentStatus.PENDING);

        mockMvc.perform(delete("/api/appointments/{id}", appointment.getAppointmentId())
                        .header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());

        assertThat(appointmentRepository
                        .findById(appointment.getAppointmentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(AppointmentStatus.PENDING);
    }

    @Test
    void cancel_completedAppointment_returns422() throws Exception {
        User customer = createCustomer();
        Appointment appointment = saveAppointment(customer, AppointmentStatus.COMPLETED);

        mockMvc.perform(delete("/api/appointments/{id}", appointment.getAppointmentId())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancel_unknownAppointment_returns404() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(delete("/api/appointments/{id}", 999_999L).header("Authorization", bearer(customer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void appointments_asAdminOrAnonymous_returns403() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(get("/api/appointments").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/appointments")).andExpect(status().isForbidden());
    }
}
