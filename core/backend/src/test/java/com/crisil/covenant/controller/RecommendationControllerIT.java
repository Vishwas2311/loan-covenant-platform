package com.crisil.covenant.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecommendationControllerIT {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().port(9319));
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void overrideBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("covenant.clients.credit-proposal.base-url", () -> "http://localhost:9319");
        registry.add("covenant.clients.attributes.base-url", () -> "http://localhost:9319");
        registry.add("covenant.clients.ml.base-url", () -> "http://localhost:9319");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CovenantMasterRepository repository;

    @BeforeEach
    void resetWireMockAndSeed() {
        wireMock.resetAll();
        repository.save(newCovenant("Universal rule", List.of()));
        repository.save(newCovenant("Retail only", List.of("RETAIL")));
        repository.save(newCovenant("SME only", List.of("SME")));
    }

    @Test
    void lookupReturnsCpDetails() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/credit-proposals/CP-1001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1001","name":"Acme","businessSegment":"RETAIL","sector":"X","approvalStatus":"APPROVED","tags":["a"]}
                                """)));

        mockMvc.perform(get("/api/v1/credit-proposals/CP-1001").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpId").value("CP-1001"))
                .andExpect(jsonPath("$.businessSegment").value("RETAIL"));
    }

    @Test
    void lookupMissingReturns404() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/credit-proposals/MISSING"))
                .willReturn(aResponse().withStatus(404)));

        mockMvc.perform(get("/api/v1/credit-proposals/MISSING").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void recommendationsReturnsUniversalAndRetail() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/attributes/CP-1001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1001","businessSegment":"RETAIL","sector":"X","attributes":{}}
                                """)));

        mockMvc.perform(get("/api/v1/credit-proposals/CP-1001/recommendations").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void onboardingRecommendationsMergesMasterRowsWhenMlReturnsNone() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/attributes/CP-1001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1001","businessSegment":"RETAIL","sector":"X","attributes":{}}
                                """)));
        // ML returns no recommendations → the merged grid is just the master rows.
        wireMock.stubFor(WireMock.post(urlEqualTo("/threshold-recommendations"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        mockMvc.perform(get("/api/v1/credit-proposals/CP-1001/onboarding-recommendations").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].source").value("RECOMMENDED"))
                .andExpect(jsonPath("$[0].thresholdSource").value("MASTER"))
                .andExpect(jsonPath("$[0].alreadyApplied").value(false));
    }

    @Test
    void createCustomCovenantAddsItToTheCpListTaggedCustom() throws Exception {
        stubCpAttributes();

        mockMvc.perform(post("/api/v1/credit-proposals/CP-1001/custom-covenants").header("X-User", "it-admin").header("X-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Board resolution","frequency":"MONTHLY",
                                 "definition":"Board must approve drawdown","assessmentType":"MANUAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.category").value("NON_FINANCIAL"))
                .andExpect(jsonPath("$.matchReason").value("Custom covenant"));

        // It now rides along in the CP's recommendation list (Universal + Retail + the custom = 3).
        mockMvc.perform(get("/api/v1/credit-proposals/CP-1001/recommendations").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    /** US-CM-24 AC6: an AUTO custom covenant is accepted and classified FINANCIAL. */
    @Test
    void createAutoCustomCovenantValidatesTheFormulaAndIsFinancial() throws Exception {
        stubCpAttributes();

        mockMvc.perform(post("/api/v1/credit-proposals/CP-1001/custom-covenants").header("X-User", "it-admin").header("X-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Deal floor","frequency":"MONTHLY",
                                 "definition":"1 > 0","assessmentType":"AUTO"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.category").value("FINANCIAL"));
    }

    /** US-CM-24 AC4: an AUTO definition that is not a formula is rejected, and nothing is created. */
    @Test
    void createAutoCustomCovenantRejectsAnInvalidFormula() throws Exception {
        stubCpAttributes();

        mockMvc.perform(post("/api/v1/credit-proposals/CP-1001/custom-covenants").header("X-User", "it-admin").header("X-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nonsense","frequency":"MONTHLY",
                                 "definition":"Board must approve drawdown","assessmentType":"AUTO"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RULE_EXPRESSION"));

        // Only the three seeded library covenants remain — the rejected custom one was never saved.
        mockMvc.perform(get("/api/v1/credit-proposals/CP-1001/recommendations").header("X-User", "it-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** US-CM-24 AC7: the assessment type is mandatory. */
    @Test
    void createCustomCovenantRequiresAnAssessmentType() throws Exception {
        mockMvc.perform(post("/api/v1/credit-proposals/CP-1001/custom-covenants").header("X-User", "it-admin").header("X-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"No type","frequency":"MONTHLY","definition":"anything"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private void stubCpAttributes() {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/attributes/CP-1001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"cpId":"CP-1001","businessSegment":"RETAIL","sector":"X","attributes":{}}
                                """)));
    }

    private static CovenantMaster newCovenant(String name, List<String> segments) {
        CovenantMaster cm = new CovenantMaster();
        cm.setName(name);
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setBusinessSegments(segments);
        cm.setActive(true);
        return cm;
    }
}
