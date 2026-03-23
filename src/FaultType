public enum FaultType {
    NONE,
    STUCK_IN_FLIGHT,
    NOZZLE_FAULT,
    CORRUPTED_MESSAGE;

    // For converting the csv string to a FaultType enum value
    public static FaultType fromString(String s) {
        if (s == null || s.trim().isEmpty()) { // If it's null or empty, the enum is none
            return NONE;
        }
        try { // else set the fault type to appropriate enum
            return FaultType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) { //any error, just assume it's none
            return NONE;
        }
    }
}
