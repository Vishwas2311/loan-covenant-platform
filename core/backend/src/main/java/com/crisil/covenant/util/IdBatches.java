package com.crisil.covenant.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Runs an {@code IN (...)} query over any number of ids in slices that stay under SQL Server's limit of
 * 2,100 parameters per statement. Bank-wide portfolios and large daily runs pass thousands of ids, and one
 * unsliced query then fails with SQL error 8003.
 */
public final class IdBatches {

    /** Ids per statement: far enough below 2,100 to leave room for a query's other parameters. */
    public static final int SIZE = 500;

    private IdBatches() {
    }

    /**
     * All rows {@code query} returns for {@code ids}, gathered slice by slice. Rows come back in slice order,
     * so a caller that needs a global order sorts the result.
     */
    public static <I, R> List<R> fetch(Collection<I> ids, Function<List<I>, ? extends Iterable<R>> query) {
        List<R> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        List<I> all = ids instanceof List<I> list ? list : new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += SIZE) {
            query.apply(all.subList(from, Math.min(all.size(), from + SIZE))).forEach(out::add);
        }
        return out;
    }
}
