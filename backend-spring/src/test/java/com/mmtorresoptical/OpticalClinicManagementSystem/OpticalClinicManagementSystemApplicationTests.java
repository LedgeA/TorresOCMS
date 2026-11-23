package com.mmtorresoptical.OpticalClinicManagementSystem;

import com.mmtorresoptical.OpticalClinicManagementSystem.config.SecurityConfig;
import com.mmtorresoptical.OpticalClinicManagementSystem.controller.SystemController;
import com.mmtorresoptical.OpticalClinicManagementSystem.repository.UserRepository;
import com.mmtorresoptical.OpticalClinicManagementSystem.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
@Import(SecurityConfig.class)
class OpticalClinicManagementSystemApplicationTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private JwtTokenProvider tokens;

    @Test
    void publicClockReturnsCurrentEpochMillisecondsWithoutLogin() throws Exception {
        long before = Instant.now().toEpochMilli();
        String body = mvc.perform(get("/api/system/time"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(Long.parseLong(body)).isBetween(before, Instant.now().toEpochMilli());
    }
}
