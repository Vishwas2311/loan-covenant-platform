package com.crisil.covenant.security;

/** Application roles (Entra ID app roles). Spring authorities carry the {@code ROLE_} prefix. */
public final class Roles {

    public static final String RM = "RM";
    public static final String APPROVER = "APPROVER";
    public static final String AUTHOR = "AUTHOR";
    public static final String SUPPORT = "SUPPORT";
    public static final String AUDITOR = "AUDITOR";
    public static final String SERVICE = "SERVICE";
    public static final String ADMIN = "ADMIN";
    /** C1: someone at the borrower; with no other role they may only use /api/v1/portal/**. */
    public static final String BORROWER = "BORROWER";

    private Roles() {
    }
}
