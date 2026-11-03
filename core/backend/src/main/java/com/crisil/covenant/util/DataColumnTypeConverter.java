package com.crisil.covenant.util;

import com.crisil.covenant.enums.DataColumnType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DataColumnTypeConverter extends LowerCaseEnumConverter<DataColumnType> {
    public DataColumnTypeConverter() {
        super(DataColumnType.class);
    }
}
