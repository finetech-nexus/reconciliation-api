package fr.nbank.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsFlowTest {

    private static final String ADMIN = "11111111-1111-1111-1111-111111111111";
    private static final String OPERATOR = "22222222-2222-2222-2222-222222222222";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void coreTypeCorrespondentsAndPurge() throws Exception {
        String profile = mvc.perform(get("/api/v1/settings/core-banking").headers(headers(ADMIN, "RECON_ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("CORE_BANKING", json.readTree(profile).get("sourceSystem").asText());
        assertEquals("FINERACT", json.readTree(profile).get("connectorType").asText());

        mvc.perform(put("/api/v1/settings/core-banking")
                        .headers(headers(ADMIN, "RECON_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"connectorType":"T24","baseUrl":"","tenant":"TN001","accountsPath":"","entriesPath":""}
                                """))
                .andExpect(status().isOk());

        String code = "CORR-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        mvc.perform(post("/api/v1/settings/correspondents")
                        .headers(headers(ADMIN, "RECON_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Nostro EUR test","bic":"DEUTDEFF","currency":"EUR","accountCode":"%s-ACC"}
                                """.formatted(code, code)))
                .andExpect(status().isCreated());

        String listed = mvc.perform(get("/api/v1/settings/correspondents").headers(headers(OPERATOR, "RECON_OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(code));

        mvc.perform(post("/api/v1/imports/core-banking/pull")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-10-16\",\"currency\":\"TND\"}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/settings/purge")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"PURGE\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/settings/purge")
                        .headers(headers(ADMIN, "RECON_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"NON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileImportAdvancesTheStatementCursor() throws Exception {
        String batch = "CUR-" + UUID.randomUUID().toString().substring(0, 8);
        String opened = mvc.perform(post("/api/v1/imports")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceSystem":"BCT","sourceType":"EXTERNAL_TRANSACTIONS","businessDate":"2026-10-16","sourceBatchId":"%s","currency":"TND","expectedRecordCount":1}
                                """.formatted(batch)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String importId = json.readTree(opened).get("importId").asText();
        mvc.perform(post("/api/v1/imports/" + importId + "/files")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType("text/csv")
                        .content("""
                                source_id;account_id;booking_date;value_date;currency;amount;direction;reference
                                %s;BCT-TND-001;2026-10-16;2026-10-16;TND;10.000;DEBIT;REF-CUR
                                """.formatted(batch)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/imports/" + importId + "/commit")
                        .headers(headers(OPERATOR, "RECON_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        String cursors = mvc.perform(get("/api/v1/settings/cursors").headers(headers(ADMIN, "RECON_ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(cursors.contains("BCT-TND-001"));
        assertTrue(cursors.contains("2026-10-16"));
    }

    private static org.springframework.http.HttpHeaders headers(String actor, String role) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Actor-Id", actor);
        headers.add("X-Actor-Roles", role);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
