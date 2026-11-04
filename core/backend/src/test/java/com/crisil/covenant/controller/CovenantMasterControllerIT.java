package com.crisil.covenant.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crisil.covenant.service.DataCatalogClient;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CovenantMasterControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DataSourceRepository dataSourceRepository;
    @Autowired private DataSourceTableRepository tableRepository;
    @Autowired private MeasureRepository measureRepository;

    @MockBean private DataCatalogClient catalogClient;

    @BeforeEach
    void setUp() {
        DataSource source = new DataSource();
        source.setName("MSSQL source");
        source.setType(DataSourceType.MSSQL);
        source.setConnectionConfig(Map.of("jdbcUrl", "jdbc:h2:mem:x"));
        source.setActive(true);
        source = dataSourceRepository.saveAndFlush(source);

        DataSourceTable table = new DataSourceTable();
        table.setDataSource(source);
        table.setName("Financials");
        table.setTableName("cp_fin");
        table.setEntityKeyColumn("cp_id");
        table.setAsOfColumn("as_of");
        table.setActive(true);
        table = tableRepository.saveAndFlush(table);

        measureRepository.saveAndFlush(measure(table, "DSCR", DataColumnType.NUMBER, "dscr"));
        measureRepository.saveAndFlush(measure(table, "auditor", DataColumnType.STRING, "auditor"));
    }

    private static Measure measure(DataSourceTable table, String code, DataColumnType type, String col) {
        Measure m = new Measure();
        m.setCode(code);
        m.setName(code);
        m.setDataSourceTable(table);
        m.setSourceColumn(col);
        m.setDefaultAggregation(FunctionKind.LATEST);
        m.setDataType(type);
        m.setActive(true);
        return m;
    }

    @Test
    void createUpdateDeactivateGetListLifecycle() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "DSCR > 1.7",
                "category", "FINANCIAL",
                "type", "DSCR",
                "description", "Debt service",
                "conditionExpression", "DSCR > 1.7",
                "assessmentType", "AUTO",
                "businessSegments", new String[] {"RETAIL"});

        String body = mockMvc.perform(post("/api/v1/covenants")
                        .header("X-User", "alice")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/covenants/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.referencedMeasures[0]").value("DSCR"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andReturn().getResponse().getContentAsString();

        String id = (String) mapper.readValue(body, Map.class).get("id");

        Map<String, Object> update = Map.of(
                "name", "DSCR > 1.8",
                "category", "FINANCIAL",
                "type", "DSCR",
                "description", "Updated",
                "conditionExpression", "DSCR > 1.8",
                "assessmentType", "AUTO",
                "businessSegments", new String[] {"RETAIL", "SME"});

        mockMvc.perform(put("/api/v1/covenants/" + id)
                        .header("X-User", "bob")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DSCR > 1.8"))
                .andExpect(jsonPath("$.businessSegments.length()").value(2))
                .andExpect(jsonPath("$.modifiedBy").value("bob"))
                // The rule changed but no CP follows this new covenant yet: nothing to re-check.
                .andExpect(jsonPath("$.recheck.checked").value(0));

        // Same rule again: no re-check, so no recheck field.
        mockMvc.perform(put("/api/v1/covenants/" + id)
                        .header("X-User", "bob")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recheck").doesNotExist());

        mockMvc.perform(get("/api/v1/covenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DSCR > 1.8"));

        mockMvc.perform(get("/api/v1/covenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(delete("/api/v1/covenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void createWithBlankNameReturns400() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "",
                "category", "FINANCIAL",
                "type", "DSCR",
                "conditionExpression", "DSCR > 1");
        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createDuplicateNameReturns409() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "Unique covenant",
                "category", "FINANCIAL",
                "type", "DSCR",
                "description", "Unique",
                "assessmentType", "AUTO",
                "conditionExpression", "DSCR > 1");
        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void getMissingReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/covenants/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void searchSupportsFilteringAndPagination() throws Exception {
        for (int i = 0; i < 3; i++) {
            boolean financial = i % 2 == 0;
            Map<String, Object> create = Map.of(
                    "name", "Cov " + i,
                    "category", financial ? "FINANCIAL" : "NON_FINANCIAL",
                    "type", financial ? "DSCR" : "AUDITOR",
                    "description", "Cov " + i,
                    "assessmentType", "AUTO",
                    "conditionExpression", financial ? "DSCR > 1" : "auditor == 'PwC'",
                    "businessSegments", new String[] {"RETAIL"});
            mockMvc.perform(post("/api/v1/covenants")
                            .contentType("application/json")
                            .content(mapper.writeValueAsString(create)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/covenants?category=FINANCIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/covenants?size=1&page=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void createManualCovenantSkipsFormulaValidation() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "Board resolution within 30 days",
                "category", "NON_FINANCIAL",
                "type", "DOCUMENT",
                "description", "Confirm the borrower submitted the board resolution.",
                "assessmentType", "MANUAL",
                "conditionExpression", "Confirm board resolution submitted within 30 days — not a formula");

        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentType").value("MANUAL"))
                .andExpect(jsonPath("$.referencedMeasures.length()").value(0));
    }

    @Test
    void createPersistsEligibilityAndNormalisesWildcard() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "GCC infrastructure rule",
                "category", "FINANCIAL",
                "type", "DSCR",
                "description", "Infra deals in GCC",
                "assessmentType", "AUTO",
                "conditionExpression", "DSCR > 1.2",
                "islamicBanking", true,
                "industries", new String[] {"INFRASTRUCTURE"},
                "countriesOfRisk", new String[] {"AE", "SA"},
                "businessSegments", new String[] {"ALL", "RETAIL"});

        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.islamicBanking").value(true))
                .andExpect(jsonPath("$.industries[0]").value("INFRASTRUCTURE"))
                .andExpect(jsonPath("$.countriesOfRisk.length()").value(2))
                .andExpect(jsonPath("$.businessSegments[0]").value("ALL"))
                .andExpect(jsonPath("$.businessSegments.length()").value(1))
                .andExpect(jsonPath("$.legalEntityTypes[0]").value("ALL"));
    }

    @Test
    void createMissingDescriptionReturns400() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "No description",
                "category", "FINANCIAL",
                "type", "DSCR",
                "assessmentType", "AUTO",
                "conditionExpression", "DSCR > 1");
        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsUnknownMeasure() throws Exception {
        Map<String, Object> create = Map.of(
                "name", "Bad rule",
                "category", "FINANCIAL",
                "type", "DSCR",
                "description", "Bad rule",
                "assessmentType", "AUTO",
                "conditionExpression", "Nonexistent > 1");
        mockMvc.perform(post("/api/v1/covenants")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(create)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RULE_EXPRESSION"));
    }
}
