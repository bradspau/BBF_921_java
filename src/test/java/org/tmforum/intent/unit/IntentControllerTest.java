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
import org.tmforum.intent.api.IntentController;
import org.tmforum.intent.exception.GlobalExceptionHandler;
import org.tmforum.intent.service.IntentService;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IntentController.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "intent.base-url=http://localhost:8000/tmf-api/intentManagement/v5")
class IntentControllerTest {

    private static final String BASE = "/tmf-api/intentManagement/v5/intent";
    private static final String VALID_ID = "11111111-1111-1111-1111-111111111111";
    private static final String UNKNOWN_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean IntentService intentService;

    private Map<String, Object> sampleIntent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", VALID_ID);
        m.put("href", "http://localhost:8000/tmf-api/intentManagement/v5/intent/" + VALID_ID);
        m.put("@type", "Intent");
        m.put("lifecycleStatus", "ACKNOWLEDGED");
        return m;
    }

    // ── LIST ────────────────────────────────────────────────────────────────────

    @Test
    void list_returns200_withTotalCountHeader() throws Exception {
        when(intentService.count(any())).thenReturn(2L);
        when(intentService.findAll(anyInt(), anyInt(), any()))
                .thenReturn(List.of(sampleIntent()));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].id").value(VALID_ID));
    }

    @Test
    void list_withFieldsParam_projectsFields() throws Exception {
        when(intentService.count(any())).thenReturn(1L);
        when(intentService.findAll(anyInt(), anyInt(), any()))
                .thenReturn(List.of(sampleIntent()));

        mvc.perform(get(BASE).param("fields", "lifecycleStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].href").exists())
                .andExpect(jsonPath("$[0].lifecycleStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[0].@type").doesNotExist());
    }

    @Test
    void list_withLifecycleStatusFilter_passesFilterToService() throws Exception {
        when(intentService.count(argThat(f -> "ACTIVE".equals(f.get("lifecycleStatus"))))).thenReturn(0L);
        when(intentService.findAll(anyInt(), anyInt(), any())).thenReturn(List.of());

        mvc.perform(get(BASE).param("lifecycleStatus", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }

    // ── CREATE ──────────────────────────────────────────────────────────────────

    @Test
    void create_returns201_withCreatedResource() throws Exception {
        Map<String, Object> body = Map.of("name", "test-intent", "@type", "Intent");
        when(intentService.create(any())).thenReturn(sampleIntent());

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(VALID_ID));
    }

    // ── GET BY ID ───────────────────────────────────────────────────────────────

    @Test
    void getById_found_returns200() throws Exception {
        when(intentService.findById(VALID_ID)).thenReturn(sampleIntent());

        mvc.perform(get(BASE + "/" + VALID_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(VALID_ID))
                .andExpect(jsonPath("$.lifecycleStatus").value("ACKNOWLEDGED"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(intentService.findById(UNKNOWN_ID)).thenReturn(null);

        mvc.perform(get(BASE + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"))
                .andExpect(jsonPath("$['@type']").value("Error"));
    }

    @Test
    void getById_withFieldsParam_projectsFields() throws Exception {
        when(intentService.findById(VALID_ID)).thenReturn(sampleIntent());

        mvc.perform(get(BASE + "/" + VALID_ID).param("fields", "lifecycleStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.@type").doesNotExist());
    }

    // ── PATCH ───────────────────────────────────────────────────────────────────

    @Test
    void patch_found_returns200() throws Exception {
        Map<String, Object> updated = new LinkedHashMap<>(sampleIntent());
        updated.put("lifecycleStatus", "ACTIVE");
        when(intentService.update(eq(VALID_ID), any())).thenReturn(updated);

        mvc.perform(patch(BASE + "/" + VALID_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lifecycleStatus\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("ACTIVE"));
    }

    @Test
    void patch_notFound_returns404() throws Exception {
        when(intentService.update(eq(UNKNOWN_ID), any())).thenReturn(null);

        mvc.perform(patch(BASE + "/" + UNKNOWN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lifecycleStatus\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"));
    }

    @Test
    void patch_invalidFsmTransition_returns400() throws Exception {
        when(intentService.update(eq(VALID_ID), any()))
                .thenThrow(new IllegalArgumentException("Invalid transition: ACKNOWLEDGED → FULFILLED"));

        mvc.perform(patch(BASE + "/" + VALID_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lifecycleStatus\":\"FULFILLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void patch_unknownState_returns422() throws Exception {
        when(intentService.update(eq(VALID_ID), any()))
                .thenThrow(new IllegalStateException("Unknown state: LIMBO"));

        mvc.perform(patch(BASE + "/" + VALID_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lifecycleStatus\":\"LIMBO\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("422"));
    }

    // ── DELETE ──────────────────────────────────────────────────────────────────

    @Test
    void delete_found_returns204() throws Exception {
        when(intentService.delete(VALID_ID)).thenReturn(true);

        mvc.perform(delete(BASE + "/" + VALID_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        when(intentService.delete(UNKNOWN_ID)).thenReturn(false);

        mvc.perform(delete(BASE + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
    }
}
