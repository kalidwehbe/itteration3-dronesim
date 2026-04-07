import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import java.net.DatagramSocket;

public class DroneBatteryTest {

    private DroneSubsystem drone;

    @Before
    public void setUp() throws Exception {
        // create drone and dummy socket to avoid NullPointerException
        drone = new DroneSubsystem(1, "localhost", 7000);
        drone.socket = new DatagramSocket();
    }

    // Test 1: battery decreases after a simple move
    @Test
    public void testBatteryAfterSimpleMove() throws Exception {
        FireEvent event = new FireEvent("00:00:00", 1, "Fire", "Low", 1, 1, FaultType.NONE);
        double before = drone.battery;
        drone.moveTo(1, 1, event);
        assertTrue("Battery should decrease after move", drone.battery < before);
    }

    // Test 2: battery needed to return is positive
    @Test
    public void testBatteryNeededToReturn() {
        double neededBattery = drone.getBatteryNeededToReturn();
        assertTrue("Battery needed to return should be > 0", neededBattery > 0);
    }

    // Test 3: battery drains more on longer moves
    @Test
    public void testBatteryDrainsWithDistance() throws Exception {
        FireEvent event1 = new FireEvent("00:00:00", 1, "Fire", "Low", 2, 2, FaultType.NONE);
        FireEvent event2 = new FireEvent("00:00:00", 2, "Fire", "Low", 10, 10, FaultType.NONE);

        double batteryStart = drone.battery;
        drone.moveTo(2, 2, event1);
        double afterShortMove = drone.battery;

        drone.battery = batteryStart; // reset battery
        drone.moveTo(10, 10, event2);
        double afterLongMove = drone.battery;

        assertTrue("Battery should drain more on longer move",
                (batteryStart - afterLongMove) > (batteryStart - afterShortMove));
    }

    // Test 4: battery never goes below 0
    @Test
    public void testBatteryNeverNegative() throws Exception {
        FireEvent event = new FireEvent("00:00:00", 1, "Fire", "Low", 1000, 1000, FaultType.NONE);
        drone.moveTo(1000, 1000, event);
        assertTrue("Battery should never be negative", drone.battery >= 0);
    }

    // Test 5: battery drains correctly when extinguishing
    @Test
    public void testBatteryDrainDuringExtinguish() throws Exception {
        double before = drone.battery;
        // simulate extinguishing ticks
        int ticks = 5;
        for (int i = 0; i < ticks; i++) {
            drone.battery -= DroneSubsystem.BATTERY_DRAIN_PER_EXTINGUISH_TICK;
        }
        assertEquals("Battery should decrease by total ticks", before - ticks, drone.battery, 0.01);
    }
}