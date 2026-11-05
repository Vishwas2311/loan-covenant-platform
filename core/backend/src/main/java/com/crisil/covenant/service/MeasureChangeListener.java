package com.crisil.covenant.service;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/** JPA callbacks on {@code Measure}: any write drops {@link MeasureCatalogService}'s cached catalog. */
public class MeasureChangeListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object measure) {
        MeasureCatalogService.measuresChanged();
    }
}
