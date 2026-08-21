package com.example.requirementrag.requirement.graph;

/** Build failure carrying the persisted snapshot that can be resumed. */
public final class RequirementGraphBuildFailureException extends RequirementGraphException {
    private final String snapshotId;

    public RequirementGraphBuildFailureException(String code, String message, String snapshotId) {
        super(code, message);
        this.snapshotId = snapshotId;
    }

    public String snapshotId() {
        return snapshotId;
    }
}
