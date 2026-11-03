package com.crisil.covenant.util;

import com.crisil.covenant.enums.TableEntityKeyLevel;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TableEntityKeyLevelConverter extends LowerCaseEnumConverter<TableEntityKeyLevel> {
    public TableEntityKeyLevelConverter() {
        super(TableEntityKeyLevel.class);
    }
}
