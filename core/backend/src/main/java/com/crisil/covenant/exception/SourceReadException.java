package com.crisil.covenant.exception;

/**
 * Reading a data source failed (the source is down, refused the call or answered garbage) before
 * anything was written to staging. Transactions that only read the source are told not to roll back
 * for it, so a caller can record the failure and carry on with the copy it already holds.
 */
public class SourceReadException extends RuntimeException {

    public SourceReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
