import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for FaultType enum (iteration 4 addition)
 * Tests that fault types are correctly parsed
 * and that invalid input defaults to NONE (faultType.NONE)
 */
public class FaultTypeTest {

    @Test
    public void testFromStringValid() {
        // Verifying that each fault type is correctly parsed from its string form
        assertEquals(FaultType.STUCK_IN_FLIGHT, FaultType.fromString("STUCK_IN_FLIGHT"));
        assertEquals(FaultType.NOZZLE_FAULT, FaultType.fromString("NOZZLE_FAULT"));
        assertEquals(FaultType.CORRUPTED_MESSAGE, FaultType.fromString("CORRUPTED_MESSAGE"));
    }

    @Test
    public void testFromStringInvalidReturnsNone() {
        // Malformed input should default to NONE (faultType.NONE) to prevent errors
        assertEquals(FaultType.NONE, FaultType.fromString("INVALID"));
        assertEquals(FaultType.NONE, FaultType.fromString(""));
        assertEquals(FaultType.NONE, FaultType.fromString(null));
    }
}