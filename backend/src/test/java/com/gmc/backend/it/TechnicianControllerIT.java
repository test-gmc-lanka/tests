package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Maintenance;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.MaintenanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TechnicianControllerIT extends IntegrationTestBase {

    @Autowired
    private MaintenanceRepository maintenanceRepository;

    private Maintenance saveTask(User technician, String status) {
        return maintenanceRepository.save(Maintenance.builder()
                .title("Service CNC router")
                .description("Replace spindle bearings")
                .status(status)
                .cost(new BigDecimal("15000.00"))
                .deadline(LocalDate.now().plusDays(3))
                .user(technician)
                .build());
    }

    @Test
    void getMyTasks_returnsOnlyTasksAssignedToCaller() throws Exception {
        User technician = createTechnician();
        User colleague = createTechnician();
        Maintenance mine = saveTask(technician, "ASSIGNED");
        saveTask(colleague, "ASSIGNED");

        mockMvc.perform(get("/api/technician/tasks").header("Authorization", bearer(technician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].maintenanceId").value(mine.getMaintenanceId()))
                .andExpect(jsonPath("$[0].technicianId").value(technician.getUserId()))
                .andExpect(jsonPath("$[0].status").value("ASSIGNED"));
    }

    @Test
    void updateTaskStatus_toInProgress_returns200() throws Exception {
        User technician = createTechnician();
        Maintenance task = saveTask(technician, "ASSIGNED");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .param("status", "IN_PROGRESS")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void updateTaskStatus_lowercaseCompleted_isNormalisedAndSaved() throws Exception {
        User technician = createTechnician();
        createAdmin();
        Maintenance task = saveTask(technician, "IN_PROGRESS");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .param("status", " completed ")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(maintenanceRepository
                        .findById(task.getMaintenanceId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo("COMPLETED");
    }

    @Test
    void updateTaskStatus_onColleaguesTask_returns422AndLeavesItUnchanged() throws Exception {
        User technician = createTechnician();
        User colleague = createTechnician();
        Maintenance task = saveTask(colleague, "ASSIGNED");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .param("status", "COMPLETED")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(maintenanceRepository
                        .findById(task.getMaintenanceId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo("ASSIGNED");
    }

    @Test
    void updateTaskStatus_withUnknownStatus_returns422() throws Exception {
        User technician = createTechnician();
        Maintenance task = saveTask(technician, "ASSIGNED");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .param("status", "ON_HOLD")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateTaskStatus_onCompletedTask_returns422() throws Exception {
        User technician = createTechnician();
        Maintenance task = saveTask(technician, "COMPLETED");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .param("status", "IN_PROGRESS")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateTaskStatus_onUnknownTask_returns404() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", 999_999L)
                        .param("status", "IN_PROGRESS")
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTaskStatus_withoutStatusParam_returns400() throws Exception {
        User technician = createTechnician();
        Maintenance task = saveTask(technician, "ASSIGNED");

        mockMvc.perform(patch("/api/technician/tasks/{id}/status", task.getMaintenanceId())
                        .header("Authorization", bearer(technician)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    }

    @Test
    void technicianEndpoints_asCustomer_returns403() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(get("/api/technician/tasks").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }
}
