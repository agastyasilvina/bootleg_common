package com.example.crud;

/**
 * Placeholder for your real application entity — the service only ever needs {@link #getId()}.
 * Delete this and import yours; the accessor name already matches the usual bean convention, so
 * {@code ApplicationAccountService} should compile against it unchanged.
 */
public class ApplicationEntity {

    private final Long id;

    public ApplicationEntity(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
