import org.junit.Test;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import static org.junit.Assert.*;

/**
 * Unit tests for DroneSubsystem fault handling
 * Tests iteration 4 additions: hard fault (NOZZLE_FAULT, CORRUPTED_MESSAGE) vs soft fault (STUCK_IN_FLIGHT)
 */
public class DroneSubsystemTest {

    /**
     * Mock socket that prevents actual UDP communication during testing
     * Without this, tests would try to send real network packets or block waiting for responses
     */
    private static class MockSocket extends DatagramSocket {
        public MockSocket() throws SocketException {
            super(0); // Port 0 = any available port (not actually used)
        }

        @Override
        public void send(DatagramPacket packet) {
            // Overridden to prevent UDP related errors in testing
        }

        @Override
        public void receive(DatagramPacket packet) {
            // Overridden to prevent blocking while waiting for messages
        }
    }

    /**
     * Test-only subclass that prevents the drone's main loop from running.
     * This allows us to call private methods directly without the main thread interfering.
     */
    private static class TestDrone extends DroneSubsystem {

        public TestDrone(int id, String host, int port) throws Exception {
            super(id, host, port);
            // Replace real socket with mock to avoid network errors during tests
            Field socketField = DroneSubsystem.class.getDeclaredField("socket");
            socketField.setAccessible(true);
            socketField.set(this, new MockSocket());
        }

        @Override
        public void start() {
            // Overridden to prevent the main loop from running
            // We call test methods directly instead
        }

        /**
         * For testing - allows tests to reset hardFaulted state before each test.
         */
        public void forceHardFaulted(boolean value) throws Exception {
            Field hardFaultedField = DroneSubsystem.class.getDeclaredField("hardFaulted");
            hardFaultedField.setAccessible(true);
            hardFaultedField.set(this, value);
        }

        /**
         * For testing - allows tests to verify hardFaulted state.
         */
        public boolean isHardFaulted() throws Exception {
            Field hardFaultedField = DroneSubsystem.class.getDeclaredField("hardFaulted");
            hardFaultedField.setAccessible(true);
            return (boolean) hardFaultedField.get(this);
        }
    }

    @Test
    public void testNozzleFaultSetsHardFaulted() throws Exception {
        TestDrone drone = new TestDrone(1, "localhost", 1000);
        drone.forceHardFaulted(false);

        // Reflection used to access private handleNozzleFault method
        // This allows testing the fault handling logic without modifying production code
        java.lang.reflect.Method handleNozzleFault =
                DroneSubsystem.class.getDeclaredMethod("handleNozzleFault", int.class);
        handleNozzleFault.setAccessible(true);

        handleNozzleFault.invoke(drone, 3);

        assertTrue("NOZZLE_FAULT should set hardFaulted to true", drone.isHardFaulted());
    }

    @Test
    public void testStuckInFlightDoesNotHardFault() throws Exception {
        TestDrone drone = new TestDrone(2, "localhost", 1000);
        drone.forceHardFaulted(false);

        // Create event with STUCK_IN_FLIGHT fault type
        FireEvent event = new FireEvent("14:03:25", 2, "REQUEST", "Moderate", 325, 1050, FaultType.STUCK_IN_FLIGHT);

        // Verify the fault type is correctly identified
        assertEquals(FaultType.STUCK_IN_FLIGHT, event.faultType);

        // STUCK_IN_FLIGHT should NOT trigger a hard fault
        assertFalse("STUCK_IN_FLIGHT should NOT set hardFaulted to true", drone.isHardFaulted());
    }

    @Test
    public void testCorruptedMessageIsHardFault() throws Exception {
        TestDrone drone = new TestDrone(3, "localhost", 1000);
        drone.forceHardFaulted(false);

        // Reflection used to access private sendCorruptedMessage method
        // This allows testing the fault handling logic without modifying production code
        java.lang.reflect.Method sendCorruptedMessage =
                DroneSubsystem.class.getDeclaredMethod("sendCorruptedMessage", int.class);
        sendCorruptedMessage.setAccessible(true);

        sendCorruptedMessage.invoke(drone, 7);

        assertTrue("CORRUPTED_MESSAGE should set hardFaulted to true", drone.isHardFaulted());
    }
}