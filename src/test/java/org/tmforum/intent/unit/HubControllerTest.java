package org.tmforum.intent.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.tmforum.intent.api.HubController;
import org.tmforum.intent.exception.GlobalExceptionHandler;
import org.tmforum.intent.graph.repositories.HubRepository;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HubController.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "intent.base-url=http://localhost:8000/tmf-api/intentManagement/v5")
class HubControllerTest {

    private static final String BASE = "/tmf-api/intentManagement/v5/hub";
    private static final String VALID_ID = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean HubRepository hubRepository;

    private Map<String, Object> sampleHub() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", VALID_ID);
        h.put("href", "http://localhost:8000/tmf-api/intentManagement/v5/hub/" + VALID_ID);
        h.put("callback", "http://example.com/notify");
        return h;
    }

    // ── LIST ────────────────────────────────────────────────────────────────────

    @Test
    void list_returns200_withHubs() throws Exception {
        when(hubRepository.findAll()).thenReturn(List.of(sampleHub()));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callback").value("http://example.com/notify"));
    }

    @Test
    void list_empty_returns200EmptyArray() throws Exception {
        when(hubRepository.findAll()).thenReturn(List.of());

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ── CREATE ──────────────────────────────────────────────────────────────────

    @Test
    void create_validCallback_returns201() throws Exception {
        when(hubRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callback\":\"http://example.com/notify\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.callback").value("http://example.com/notify"))
                .andExpect(jsonPath("$.href").value(org.hamcrest.Matchers.containsString("/hub/")));
    }

    @Test
    void create_missingCallback_returns400() throws Exception {
        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void create_emptyCallback_returns400() throws Exception {
        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callback\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET BY ID ───────────────────────────────────────────────────────────────

    @Test
    void getById_found_returns200() throws Exception {
        when(hubRepository.findById(VALID_ID)).thenReturn(sampleHub());

        mvc.perform(get(BASE + "/" + VALID_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(VALID_ID));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(hubRepository.findById(UNKNOWN_ID)).thenReturn(null);

        mvc.perform(get(BASE + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$['@type']").value("Error"));
    }

    // ── DELETE ──────────────────────────────────────────────────────────────────

    @Test
    void delete_found_returns204() throws Exception {
        when(hubRepository.delete(VALID_ID)).thenReturn(true);

        mvc.perform(delete(BASE + "/" + VALID_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        when(hubRepository.delete(UNKNOWN_ID)).thenReturn(false);

        mvc.perform(delete(BASE + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
    }
}
