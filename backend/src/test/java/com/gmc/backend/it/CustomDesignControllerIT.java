package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.CustomDesign;
import com.gmc.backend.model.CustomDesignStatus;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.CustomDesignRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class CustomDesignControllerIT extends IntegrationTestBase {

    @Autowired
    private CustomDesignRepository customDesignRepository;

    private static MockMultipartFile designFile(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", "%PDF-1.4 test".getBytes());
    }

    @Test
    void submit_withPdfFile_returns201PendingReviewAndStoresFile() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/custom-designs")
                        .file(designFile("cabinet.pdf"))
                        .param("designName", "Corner cabinet")
                        .param("description", "Oak, 2 m tall")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.designName").value("Corner cabinet"))
                .andExpect(jsonPath("$.userId").value(customer.getUserId()))
                .andExpect(jsonPath("$.filePath").value(startsWith("/uploads/designs/" + customer.getUserId() + "/")));

        assertThat(customDesignRepository.findByUser_UserId(customer.getUserId()))
                .hasSize(1);
    }

    @Test
    void submit_withoutFile_returns201WithNoFilePath() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/custom-designs")
                        .param("designName", "Bookshelf")
                        .param("description", "Five shelves")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filePath").value(nullValue()));
    }

    @Test
    void submit_withSvgFile_returns400() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/custom-designs")
                        .file(designFile("drawing.svg"))
                        .param("designName", "Logo sign")
                        .param("description", "Engraved")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILE"));

        assertThat(customDesignRepository.findByUser_UserId(customer.getUserId()))
                .isEmpty();
    }

    @Test
    void submit_withoutDesignName_returns400() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(multipart("/api/custom-designs")
                        .param("description", "No name given")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    }

    @Test
    void getMyDesigns_returnsOnlyCallersDesigns() throws Exception {
        User customer = createCustomer();
        User other = createCustomer();
        customDesignRepository.save(CustomDesign.builder()
                .designName("Mine")
                .description("d")
                .status(CustomDesignStatus.PENDING_REVIEW)
                .createdDate(LocalDateTime.now())
                .user(customer)
                .build());
        customDesignRepository.save(CustomDesign.builder()
                .designName("Theirs")
                .description("d")
                .status(CustomDesignStatus.PENDING_REVIEW)
                .createdDate(LocalDateTime.now())
                .user(other)
                .build());

        mockMvc.perform(get("/api/custom-designs").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].designName").value("Mine"));
    }

    @Test
    void customDesigns_asTechnician_returns403() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(get("/api/custom-designs").header("Authorization", bearer(technician)))
                .andExpect(status().isForbidden());
    }
}
