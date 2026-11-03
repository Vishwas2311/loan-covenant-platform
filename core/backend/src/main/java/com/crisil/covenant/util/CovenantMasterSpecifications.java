package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantCategory;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import com.crisil.covenant.entity.CovenantMaster;

public final class CovenantMasterSpecifications {

    private CovenantMasterSpecifications() {}

    public static Specification<CovenantMaster> matching(
            String search,
            CovenantCategory category,
            String businessSegment,
            Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Custom covenants are CP-scoped, never part of the shared library or the recommendation
            // matching pool — they surface only under their owning CP.
            predicates.add(cb.isFalse(root.get("custom")));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), pattern);
                Predicate descMatch = cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern);
                predicates.add(cb.or(nameMatch, descMatch));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (businessSegment != null && !businessSegment.isBlank()) {
                predicates.add(cb.like(root.get("businessSegments"),
                        "%\"" + businessSegment + "\"%"));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
