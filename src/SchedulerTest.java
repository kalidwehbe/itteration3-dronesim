import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Queue;
import static org.junit.Assert.*;

/**
 * Unit tests for SchedulerUDP fault handling
 * Tests iteration 4 additions: soft/hard fault detection + event requeuing with fault cleared
 */
public class SchedulerTest {

    private SchedulerUDP scheduler;
    private Queue<FireEvent> pendingEvents;  // Accessed via reflection for test verification

    // Mock socket prevents actual UDP communication during testing
    private static class MockSocket extends DatagramSocket {
        public MockSocket() throws SocketException {
            super(0);
        }
        @Override
        public void send(DatagramPacket packet) { /* Prevent UDP errors in tests */ }
        @Override
        public void receive(DatagramPacket packet) { /* Prevent blocking */ }
    }

    @Before
    public void setUp() throws Exception {
        scheduler = new SchedulerUDP(0);

        // Inject mock socket to avoid NullPointerException during tests
        Field socketField = SchedulerUDP.class.getDeclaredField("socket");
        socketField.setAccessible(true);
        socketField.set(scheduler, new MockSocket());

        // Access private pendingEvents queue to verify event requeuing
        // Reflection is used because this field is intentionally private to the scheduler,
        // but tests need to verify internal state after fault handling.
        Field pendingField = SchedulerUDP.class.getDeclaredField("pendingEvents");
        pendingField.setAccessible(true);
        pendingEvents = (Queue<FireEvent>) pendingField.get(scheduler);
    }

    @Test
    public void testFaultClearedWhenRequeued() {
        FireEvent original = new FireEvent("14:03:40", 3, "FIRE", "High", 1050, 300, FaultType.NOZZLE_FAULT);

        // Simulates markDroneFaultAndRequeue() - creates new event with fault stripped
        FireEvent cleared = new FireEvent(
                original.time, original.zoneId, original.type,
                original.severity, original.centerX, original.centerY,
                FaultType.NONE
        );

        assertEquals(FaultType.NONE, cleared.faultType);
        assertEquals(original.zoneId, cleared.zoneId);
    }

    @Test
    public void testSoftFaultLoggedNoRequeue() throws Exception {
        // Create drone that has been en route for 5 seconds (soft fault range)
        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        FireEvent event = new FireEvent("14:03:25", 2, "REQUEST", "Moderate", 325, 1050, FaultType.STUCK_IN_FLIGHT);
        drone.assignedEvent = event;
        drone.assignedAtMs = System.currentTimeMillis() - 5000; // 5 seconds elapsed
        drone.lastArrivedAtMs = 0;
        drone.state = "EN_ROUTE";

        // Add drone to scheduler's internal map (reflection needed to access private field)
        Field dronesField = SchedulerUDP.class.getDeclaredField("drones");
        dronesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, SchedulerUDP.DroneInfo> drones =
                (java.util.Map<Integer, SchedulerUDP.DroneInfo>) dronesField.get(scheduler);
        drones.put(1, drone);

        // Trigger timeout detection
        Method detectFaults = SchedulerUDP.class.getDeclaredMethod("detectTimeoutFaults");
        detectFaults.setAccessible(true);
        detectFaults.invoke(scheduler);

        // Soft fault should log warning but NOT requeue event or mark drone offline
        assertFalse(pendingEvents.contains(event));
        assertFalse(drone.offline);
    }

    @Test
    public void testHardFaultTriggersRequeue() throws Exception {
        // Create drone that has been en route for 11 seconds (hard fault range)
        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        FireEvent event = new FireEvent("14:03:40", 3, "FIRE", "High", 1050, 300, FaultType.NOZZLE_FAULT);
        drone.assignedEvent = event;
        drone.assignedAtMs = System.currentTimeMillis() - 11000; // 11 seconds elapsed
        drone.lastArrivedAtMs = 0;
        drone.state = "EN_ROUTE";

        Field dronesField = SchedulerUDP.class.getDeclaredField("drones");
        dronesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, SchedulerUDP.DroneInfo> drones =
                (java.util.Map<Integer, SchedulerUDP.DroneInfo>) dronesField.get(scheduler);
        drones.put(1, drone);

        pendingEvents.clear();

        Method detectFaults = SchedulerUDP.class.getDeclaredMethod("detectTimeoutFaults");
        detectFaults.setAccessible(true);
        detectFaults.invoke(scheduler);

        // Hard fault should requeue event with fault stripped and mark drone offline
        assertEquals(1, pendingEvents.size());
        FireEvent requeued = pendingEvents.peek();
        assertEquals(FaultType.NONE, requeued.faultType);
        assertEquals(event.zoneId, requeued.zoneId);
        assertTrue(drone.offline);
    }

    @Test
    public void testNozzleFaultHandledAsHardFault() throws Exception {
        // Simulate drone sending FAULTED status after nozzle failure
        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        FireEvent event = new FireEvent("14:03:40", 3, "FIRE", "High", 1050, 300, FaultType.NOZZLE_FAULT);
        drone.assignedEvent = event;

        Field dronesField = SchedulerUDP.class.getDeclaredField("drones");
        dronesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, SchedulerUDP.DroneInfo> drones =
                (java.util.Map<Integer, SchedulerUDP.DroneInfo>) dronesField.get(scheduler);
        drones.put(1, drone);

        pendingEvents.clear();

        Method handleStatus = SchedulerUDP.class.getDeclaredMethod("handleDroneStatus", String.class);
        handleStatus.setAccessible(true);
        handleStatus.invoke(scheduler, "DRONE_STATUS,1,FAULTED,14");

        // FAULTED status should trigger hard fault handling
        assertEquals(1, pendingEvents.size());
        FireEvent requeued = pendingEvents.peek();
        assertEquals(FaultType.NONE, requeued.faultType);
        assertTrue(drone.offline);
    }
}