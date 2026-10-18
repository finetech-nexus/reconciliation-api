package fr.nbank.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationFlowTest {

    private static final String OPERATOR = "22222222-2222-2222-2222-222222222222";
    private static final String ANALYST = "33333333-3333-3333-3333-333333333333";
    private static final String APPROVER = "44444444-4444-4444-4444-444444444444";
    private static final String AUDITOR = "55555555-5555-5555-5555-555555555555";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void importsReconcilesAndEnforcesApproval() throws Exception {
        String batch = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        String gl = open("CORE_BANKING", "ACCOUNTING_ENTRIES", batch + "-GL");
        stage(gl, """
                source_id;account_id;booking_date;value_date;currency;amount;direction;reference
                GL-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;CREDIT;REF-001
                GL-002;BCT-TND-001;2026-10-16;2026-10-16;TND;11999.000;DEBIT;REF-002
                GL-003;BCT-TND-001;2026-10-16;2026-10-16;TND;3000.000;CREDIT;REF-005
                GL-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;CREDIT;REF-005
                GL-005;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
                GL-006;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
                GL-007;BCT-TND-001;2026-10-16;2026-10-16;TND;1500.000;CREDIT;REF-ORPHAN
                """);
        JsonNode loaded = commit(gl, null);
        assertEquals("LOADED", loaded.get("status").asText());
        assertEquals(7, loaded.get("acceptedRecords").asInt());

        String replay = open("CORE_BANKING", "ACCOUNTING_ENTRIES", batch + "-GL");
        assertEquals(gl, replay);

        String external = open("BCT", "EXTERNAL_TRANSACTIONS", batch + "-EXT");
        stage(external, """
                source_id;account_id;booking_date;value_date;currency;amount;direction;reference
                TX-001;BCT-TND-001;2026-10-16;2026-10-16;TND;25000.000;DEBIT;REF-001
                TX-002;BCT-TND-001;2026-10-16;2026-10-16;TND;12000.000;CREDIT;REF-002
                TX-003;BCT-TND-001;2026-10-16;2026-10-16;TND;8000.000;DEBIT;REF-005
                TX-004;BCT-TND-001;2026-10-16;2026-10-16;TND;5000.000;DEBIT;REF-AMB
                TX-005;BCT-TND-001;2026-10-16;2026-10-16;TND;700.000;DEBIT;REF-NONE
                """);
        assertEquals("LOADED", commit(external, null).get("status").asText());

        JsonNode run = json.readTree(mvc.perform(post("/api/v1/reconciliations")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountCode":"BCT-TND-001","currency":"TND","periodFrom":"2026-10-16","periodTo":"2026-10-16"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertEquals(2, run.get("matchedCount").asInt());
        assertTrue(run.get("exceptionCount").asInt() >= 4);

        JsonNode exceptions = json.readTree(mvc.perform(get("/api/v1/exceptions?size=50")
                        .headers(headers(ANALYST, "RECON_ANALYST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String exceptionId = exceptions.get("content").get(0).get("exceptionId").asText();

        mvc.perform(post("/api/v1/exceptions/" + exceptionId + "/resolve")
                        .headers(headers(AUDITOR, "RECON_AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposal\":\"IGNORE\",\"comment\":\"hors rôle\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/exceptions/" + exceptionId + "/resolve")
                        .headers(headers(ANALYST, "RECON_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposal\":\"JUSTIFIED\",\"comment\":\"écart de change\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/exceptions/" + exceptionId + "/approve")
                        .headers(headers(ANALYST, "RECON_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"moi-même\"}"))
                .andExpect(status().isForbidden());

        JsonNode approved = json.readTree(mvc.perform(post("/api/v1/exceptions/" + exceptionId + "/approve")
                        .headers(headers(APPROVER, "RECON_APPROVER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"validé\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("RESOLVED", approved.get("status").asText());
        assertNotEquals(ANALYST, approved.get("actions").get(1).get("actorId").asText());
    }

    @Test
    void rejectsMalformedFileAndBlocksControlBreak() throws Exception {
        String batch = "BAD-" + UUID.randomUUID().toString().substring(0, 8);
        String broken = open("BCT", "EXTERNAL_TRANSACTIONS", batch);
        MvcResult rejected = mvc.perform(post("/api/v1/imports/" + broken + "/files")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType("text/csv")
                        .content("not,a,valid,header\n1,2,3"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertEquals("REJECTED", json.readTree(rejected.getResponse().getContentAsString()).get("status").asText());
        JsonNode fetched = json.readTree(mvc.perform(get("/api/v1/imports/" + broken)
                        .headers(headers(OPERATOR, "RECON_OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("REJECTED", fetched.get("status").asText());
        assertEquals(0, fetched.get("acceptedRecords").asInt());

        String control = open("BCT", "EXTERNAL_TRANSACTIONS", batch + "-CTL");
        stage(control, """
                source_id;account_id;booking_date;value_date;currency;amount;direction;reference
                TX-CTL;BCT-TND-001;2026-10-16;2026-10-16;TND;10.000;DEBIT;REF-CTL
                """);
        JsonNode quarantined = commit(control, "99.000");
        assertEquals("QUARANTINED", quarantined.get("status").asText());
        assertEquals(0, quarantined.get("acceptedRecords").asInt());
    }

    private String open(String source, String type, String sourceBatchId) throws Exception {
        String response = mvc.perform(post("/api/v1/imports")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceSystem":"%s","sourceType":"%s","businessDate":"2026-10-16","sourceBatchId":"%s","currency":"TND","expectedRecordCount":7}
                                """.formatted(source, type, sourceBatchId)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("importId").asText();
    }

    private void stage(String importId, String csv) throws Exception {
        mvc.perform(post("/api/v1/imports/" + importId + "/files")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isOk());
    }

    private JsonNode commit(String importId, String controlTotal) throws Exception {
        String body = controlTotal == null ? "{}" : "{\"controlTotal\":" + controlTotal + "}";
        String response = mvc.perform(post("/api/v1/imports/" + importId + "/commit")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static org.springframework.http.HttpHeaders headers(String actor, String role) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Actor-Id", actor);
        headers.add("X-Actor-Roles", role);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
