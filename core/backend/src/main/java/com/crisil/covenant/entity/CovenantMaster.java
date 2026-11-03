package com.crisil.covenant.entity;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.entity.AuditableEntity;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.util.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import java.util.ArrayList;
import java.util.List;
import com.crisil.covenant.enums.AssessmentType;

@Entity
@Table(name = "cov_covenant_master")
public class CovenantMaster extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "category", nullable = false, length = 32)
    private CovenantCategory category;

    @Column(name = "description", length = 2000)
    private String description;

    /**
     * The measure-based DSL condition, e.g. {@code AVG(PAT, '2y') > 1 AND DSCR > 1.2}. It must
     * evaluate to a boolean; TRUE means the covenant requirement is satisfied (COMPLIANT).
     */
    @Column(name = "condition_expression", nullable = false, length = 2000)
    private String conditionExpression;

    /** Measure codes referenced by the condition (denormalised for batching the value lookups). */
    @Convert(converter = StringListConverter.class)
    @Column(name = "referenced_measures", nullable = false, length = 2000)
    private List<String> referencedMeasures = new ArrayList<>();

    /** Segment eligibility (US-CM-01). Empty or {@code ["ALL"]} = wildcard (matches any segment). */
    @Convert(converter = StringListConverter.class)
    @Column(name = "business_segments", nullable = false, length = 2000)
    private List<String> businessSegments = new ArrayList<>();

    /** Shariah-compliant deals (true) vs conventional (false). Exact match in recommendation. */
    @Column(name = "islamic_banking", nullable = false)
    private boolean islamicBanking;

    /** US-CM-03: AUTO = measure formula (validated/evaluated); MANUAL = free-text instruction. */
    @Column(name = "assessment_type", nullable = false, length = 16)
    private AssessmentType assessmentType = AssessmentType.AUTO;

    @Convert(converter = StringListConverter.class)
    @Column(name = "industries", nullable = false, length = 2000)
    private List<String> industries = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "countries_of_risk", nullable = false, length = 2000)
    private List<String> countriesOfRisk = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "countries_of_incorporation", nullable = false, length = 2000)
    private List<String> countriesOfIncorporation = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "credit_proposal_types", nullable = false, length = 2000)
    private List<String> creditProposalTypes = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "legal_entity_types", nullable = false, length = 2000)
    private List<String> legalEntityTypes = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "facility_types", nullable = false, length = 2000)
    private List<String> facilityTypes = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "collateral_types", nullable = false, length = 2000)
    private List<String> collateralTypes = new ArrayList<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** US-FREQ-07: recommended monitoring cadence; pre-fills the per-CP frequency in onboarding. */
    @Column(name = "default_frequency", nullable = false, length = 16)
    private Frequency defaultFrequency = Frequency.MONTHLY;

    /** Quartz cron used only when {@link #defaultFrequency} is CUSTOM; otherwise {@code null}. */
    @Column(name = "default_cron", length = 64)
    private String defaultCron;

    /** True for a CP-scoped custom covenant (not a shared library item); see {@link #ownerCpId}. */
    @Column(name = "is_custom", nullable = false)
    private boolean custom;

    /** The CP that owns this covenant when {@link #custom}; {@code null} for library covenants. */
    @Column(name = "owner_cp_id", length = 64)
    private String ownerCpId;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CovenantCategory getCategory() {
        return category;
    }

    public void setCategory(CovenantCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    public List<String> getReferencedMeasures() {
        return referencedMeasures;
    }

    public void setReferencedMeasures(List<String> referencedMeasures) {
        this.referencedMeasures =
                referencedMeasures == null ? new ArrayList<>() : new ArrayList<>(referencedMeasures);
    }

    public List<String> getBusinessSegments() {
        return businessSegments;
    }

    public void setBusinessSegments(List<String> businessSegments) {
        this.businessSegments = businessSegments == null ? new ArrayList<>() : new ArrayList<>(businessSegments);
    }

    public boolean isIslamicBanking() {
        return islamicBanking;
    }

    public void setIslamicBanking(boolean islamicBanking) {
        this.islamicBanking = islamicBanking;
    }

    public AssessmentType getAssessmentType() {
        return assessmentType;
    }

    public void setAssessmentType(AssessmentType assessmentType) {
        this.assessmentType = assessmentType == null ? AssessmentType.AUTO : assessmentType;
    }

    public List<String> getIndustries() {
        return industries;
    }

    public void setIndustries(List<String> industries) {
        this.industries = industries == null ? new ArrayList<>() : new ArrayList<>(industries);
    }

    public List<String> getCountriesOfRisk() {
        return countriesOfRisk;
    }

    public void setCountriesOfRisk(List<String> countriesOfRisk) {
        this.countriesOfRisk = countriesOfRisk == null ? new ArrayList<>() : new ArrayList<>(countriesOfRisk);
    }

    public List<String> getCountriesOfIncorporation() {
        return countriesOfIncorporation;
    }

    public void setCountriesOfIncorporation(List<String> countriesOfIncorporation) {
        this.countriesOfIncorporation =
                countriesOfIncorporation == null ? new ArrayList<>() : new ArrayList<>(countriesOfIncorporation);
    }

    public List<String> getCreditProposalTypes() {
        return creditProposalTypes;
    }

    public void setCreditProposalTypes(List<String> creditProposalTypes) {
        this.creditProposalTypes =
                creditProposalTypes == null ? new ArrayList<>() : new ArrayList<>(creditProposalTypes);
    }

    public List<String> getLegalEntityTypes() {
        return legalEntityTypes;
    }

    public void setLegalEntityTypes(List<String> legalEntityTypes) {
        this.legalEntityTypes = legalEntityTypes == null ? new ArrayList<>() : new ArrayList<>(legalEntityTypes);
    }

    public List<String> getFacilityTypes() {
        return facilityTypes;
    }

    public void setFacilityTypes(List<String> facilityTypes) {
        this.facilityTypes = facilityTypes == null ? new ArrayList<>() : new ArrayList<>(facilityTypes);
    }

    public List<String> getCollateralTypes() {
        return collateralTypes;
    }

    public void setCollateralTypes(List<String> collateralTypes) {
        this.collateralTypes = collateralTypes == null ? new ArrayList<>() : new ArrayList<>(collateralTypes);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Never null — a covenant with no stored frequency is treated as Monthly (US-FREQ-07 AC6). */
    public Frequency getDefaultFrequency() {
        return defaultFrequency == null ? Frequency.MONTHLY : defaultFrequency;
    }

    public void setDefaultFrequency(Frequency defaultFrequency) {
        this.defaultFrequency = defaultFrequency == null ? Frequency.MONTHLY : defaultFrequency;
    }

    public String getDefaultCron() {
        return defaultCron;
    }

    public void setDefaultCron(String defaultCron) {
        this.defaultCron = defaultCron;
    }

    public boolean isCustom() {
        return custom;
    }

    public void setCustom(boolean custom) {
        this.custom = custom;
    }

    public String getOwnerCpId() {
        return ownerCpId;
    }

    public void setOwnerCpId(String ownerCpId) {
        this.ownerCpId = ownerCpId;
    }
}
