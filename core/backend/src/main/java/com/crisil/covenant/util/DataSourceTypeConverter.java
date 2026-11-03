package com.crisil.covenant.util;

import com.crisil.covenant.enums.DataSourceType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DataSourceTypeConverter extends LowerCaseEnumConverter<DataSourceType> {
    public DataSourceTypeConverter() {
        super(DataSourceType.class);
    }
}
