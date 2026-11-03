package com.crisil.covenant.util;

import com.crisil.covenant.enums.TableLayout;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TableLayoutConverter extends LowerCaseEnumConverter<TableLayout> {
    public TableLayoutConverter() {
        super(TableLayout.class);
    }
}
