import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;

public class DroneSubsystemTest {

    private DroneSubsystem drone;
    private Scheduler scheduler;
    private FireGUI gui;

    private Set<DroneSubsystem> available;
    private Set<DroneSubsystem> busy;

    @Before
    public void setUp() {
        scheduler = new Scheduler();
        gui = new FireGUI();

        drone = new DroneSubsystem(scheduler, gui);

        available = ConcurrentHashMap.newKeySet();
        busy = ConcurrentHashMap.newKeySet();

        available.add(drone);
        scheduler.assignDroneSets(available, busy);

        // speed up simulation
        FirefightingSimulation.SIMULATION_SPEED_UP_FACTOR = 1000;

        drone.start();
        scheduler.start();
    }

    @After
    public void tearDown() {
        drone.interrupt();
        scheduler.interrupt();
    }

    // -------- TESTS --------

    @Test
    public void testAssignEvent() {
        FireEvent event = new FireEvent("10:00", 1, "Fire", "Low", 10, 10);

        drone.assignEvent(event);

        assertEquals(event, drone.assignedEvent);
    }

    @Test
    public void testDroneBecomesBusy() throws InterruptedException {
        FireEvent event = new FireEvent("10:00", 1, "Fire", "Low", 10, 10);

        scheduler.submitEvent(event);

        // wait until drone becomes busy
        int attempts = 0;
        while (busy.isEmpty() && attempts < 20) {
            Thread.sleep(50);
            attempts++;
        }
        System.out.println(attempts);

        assertTrue(busy.contains(drone));
        assertFalse(available.contains(drone));
    }

    @Test
    public void testDroneReturnsToAvailableAfterFinish() throws InterruptedException {
        FireEvent event = new FireEvent("10:00", 1, "Fire", "Low", 10, 10);

        scheduler.submitEvent(event);

        Thread.sleep(800); // allow full cycle

        assertTrue(available.contains(drone));
    }
}