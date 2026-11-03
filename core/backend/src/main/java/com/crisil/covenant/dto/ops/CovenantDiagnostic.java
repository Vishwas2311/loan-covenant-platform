package com.crisil.covenant.dto.ops;

import java.util.List;

/**
 * The production-support trace for one covenant applied to a CP: the condition that would be
 * evaluated, whether it compiles, and a per-term breakdown of where the pipeline would (or would not)
 * resolve a value. Read model only — coverage-excluded ({@code dto/**}).
 *
 * @param appliedCovenantId   the applied-covenant id
 * @param covenantMasterId    the library covenant id it derives from
 * @param covenantName        the covenant's display name
 * @param assessmentType      {@code AUTO} (formula-evaluated) or {@code MANUAL} (person-assessed)
 * @param conditionExpression the effective condition (per-CP override, else the master's)
 * @param compiled            whether the condition parses and type-checks against the active catalog
 * @param compileError        the compile error message when {@link #compiled} is false, else
 *                            {@code null}
 * @param wouldResolve        whether every term resolves (the assessment would not report missing
 *                            values); always false when the condition does not compile
 * @param terms               the per-term diagnostics, in formula order (empty for a MANUAL covenant
 *                            or one that fails to compile)
 */
public record CovenantDiagnostic(
        String appliedCovenantId,
        String covenantMasterId,
        String covenantName,
        String assessmentType,
        String conditionExpression,
        boolean compiled,
        String compileError,
        boolean wouldResolve,
        List<TermDiagnostic> terms) {
}
