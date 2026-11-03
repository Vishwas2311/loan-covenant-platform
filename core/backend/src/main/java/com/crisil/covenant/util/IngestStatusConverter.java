package com.crisil.covenant.util;

import com.crisil.covenant.enums.IngestStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class IngestStatusConverter extends LowerCaseEnumConverter<IngestStatus> {
    public IngestStatusConverter() {
        super(IngestStatus.class);
    }
}
