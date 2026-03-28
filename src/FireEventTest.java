import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for FireEvent iteration 4 additions
 * Tests that fault type is properly stored in FireEvent objects and
 * correctly displayed in the toString() method for logging purposes
 */
public class FireEventTest {

    @Test
    public void testEventWithNozzleFault() {
        // Creating an event with NOZZLE_FAULT and verifying that the fault type is stored correctly
        FireEvent event = new FireEvent("14:03:40", 3, "FIRE", "High", 1050, 300, FaultType.NOZZLE_FAULT);
        assertEquals(FaultType.NOZZLE_FAULT, event.faultType);
    }

    @Test
    public void testEventWithStuckInFlight() {
        // Creating an event with STUCK_IN_FLIGHT and verifying that the fault type is stored correctly
        FireEvent event = new FireEvent("14:03:25", 2, "REQUEST", "Moderate", 325, 1050, FaultType.STUCK_IN_FLIGHT);
        assertEquals(FaultType.STUCK_IN_FLIGHT, event.faultType);
    }

    @Test
    public void testEventWithCorruptedMessage() {
        // Creating an event with CORRUPTED_MESSAGE and verifying that the fault type is stored correctly
        FireEvent event = new FireEvent("14:04:00", 7, "REQUEST", "Low", 975, 1050, FaultType.CORRUPTED_MESSAGE);
        assertEquals(FaultType.CORRUPTED_MESSAGE, event.faultType);
    }

    @Test
    public void testEventWithNoFault() {
        // Creating an event with no fault and verifying that fault type defaults to NONE
        FireEvent event = new FireEvent("14:03:15", 1, "FIRE", "Low", 350, 300, FaultType.NONE);
        assertEquals(FaultType.NONE, event.faultType);
    }

    @Test
    public void testToStringIncludesFault() {
        // toString() is used for logging and debugging -> fault type needs to be visible
        FireEvent event = new FireEvent("14:03:40", 3, "FIRE", "High", 1050, 300, FaultType.NOZZLE_FAULT);
        assertTrue("toString() should contain the fault type", event.toString().contains("NOZZLE_FAULT"));
    }

    @Test
    public void testToStringWithNoFault() {
        // Even when there's no fault, toString() should show FAULT=NONE
        FireEvent event = new FireEvent("14:03:15", 1, "FIRE", "Low", 350, 300, FaultType.NONE);
        assertTrue("toString() should indicate FAULT=NONE when no fault present",
                event.toString().contains("FAULT=NONE"));
    }
}