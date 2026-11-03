package com.crisil.covenant.service;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.dto.CreateMeasureRequest;
import com.crisil.covenant.dto.MeasureResponse;
import com.crisil.covenant.dto.UpdateMeasureRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;

/** CRUD for the measure library. Measures are referenced by {@code code} in covenant formulas. */
@Service
@Transactional
public class MeasureService {

    private final MeasureRepository repository;
    private final DataSourceTableRepository tableRepository;

    public MeasureService(MeasureRepository repository, DataSourceTableRepository tableRepository) {
        this.repository = repository;
        this.tableRepository = tableRepository;
    }

    public MeasureResponse create(CreateMeasureRequest request) {
        if (repository.existsByCodeIgnoreCase(request.code())) {
            throw new DuplicateResourceException(
                    "Measure with code '" + request.code() + "' already exists");
        }
        Measure measure = new Measure();
        measure.setCode(request.code());
        apply(measure, request.name(), request.description(), request.dataSourceTableId(),
                request.sourceColumn(), request.sourceCode(), request.defaultAggregation(),
                request.dataType(), request.unit());
        measure.setActive(true);
        return MeasureResponse.from(repository.saveAndFlush(measure));
    }

    public MeasureResponse update(String id, UpdateMeasureRequest request) {
        Measure measure = findOrThrow(id);
        if (!measure.getCode().equalsIgnoreCase(request.code())
                && repository.existsByCodeIgnoreCase(request.code())) {
            throw new DuplicateResourceException(
                    "Measure with code '" + request.code() + "' already exists");
        }
        measure.setCode(request.code());
        apply(measure, request.name(), request.description(), request.dataSourceTableId(),
                request.sourceColumn(), request.sourceCode(), request.defaultAggregation(),
                request.dataType(), request.unit());
        return MeasureResponse.from(repository.saveAndFlush(measure));
    }

    public MeasureResponse setActive(String id, boolean active) {
        Measure measure = findOrThrow(id);
        measure.setActive(active);
        return MeasureResponse.from(repository.saveAndFlush(measure));
    }

    @Transactional(readOnly = true)
    public MeasureResponse get(String id) {
        return MeasureResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<MeasureResponse> list(Boolean activeOnly) {
        List<Measure> measures = Boolean.TRUE.equals(activeOnly)
                ? repository.findByActiveTrueOrderByCodeAsc()
                : repository.findAllByOrderByCodeAsc();
        return measures.stream().map(MeasureResponse::from).toList();
    }

    private void apply(Measure measure, String name, String description, String dataSourceTableId,
                       String sourceColumn, String sourceCode,
                       com.crisil.covenant.formula.FunctionKind defaultAggregation,
                       com.crisil.covenant.enums.DataColumnType dataType, String unit) {
        DataSourceTable table = tableRepository.findById(dataSourceTableId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Data source table " + dataSourceTableId + " not found"));
        measure.setName(name);
        measure.setDescription(description);
        measure.setDataSourceTable(table);

        // The measure→storage mapping depends on the backing table's layout: a COLUMNAR table reads a
        // physical column (sourceColumn); a KEY_VALUE table matches rows by code (sourceCode).
        String column = blankToNull(sourceColumn);
        String code = blankToNull(sourceCode);
        if (table.getLayout() == TableLayout.KEY_VALUE) {
            if (code == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEASURE_MAPPING",
                        "Measures on a KEY_VALUE table require a source code");
            }
            column = null;
        } else {
            if (column == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEASURE_MAPPING",
                        "Measures on a COLUMNAR table require a source column");
            }
            code = null;
        }
        measure.setSourceColumn(column);
        measure.setSourceCode(code);
        measure.setDefaultAggregation(defaultAggregation);
        measure.setDataType(dataType);
        measure.setUnit(unit == null || unit.isBlank() ? null : unit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Measure findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Measure " + id + " not found"));
    }
}
