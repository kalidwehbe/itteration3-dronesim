import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import java.util.HashSet;

import static org.junit.Assert.*;

public class SchedulerTest {

    private Scheduler scheduler;
    private HashSet<DroneSubsystem> available;
    private HashSet<DroneSubsystem> busy;

    private DroneSubsystem d1;
    private DroneSubsystem d2;
    private DroneSubsystem d3;

    @Before
    public void setUp() {
        scheduler = new Scheduler();

        available = new HashSet<>();
        busy = new HashSet<>();

        d1 = new DroneSubsystem(null, null);
        d2 = new DroneSubsystem(null, null);
        d3 = new DroneSubsystem(null, null);

        available.add(d1);
        available.add(d2);
        available.add(d3);

        scheduler.assignDroneSets(available, busy);
    }

    @After
    public void tearDown() {
        scheduler = null;
        available.clear();
        busy.clear();
    }

    // ---------------- TESTS ----------------

    @Test
    public void testMultipleDronesExist() {
        assertEquals(3, available.size());
        assertTrue(busy.isEmpty());
    }

    @Test
    public void testDroneBecomesBusy() throws InterruptedException {
        FireEvent event = new FireEvent("10:00", 1, "Fire", "Low", 0, 0);

        scheduler.submitEvent(event);
        scheduler.assignEventToPreferredDrone();

        assertEquals(1, busy.size());
        assertEquals(2, available.size());
    }

    @Test
    public void testDroneReturnsToAvailable() {
        busy.add(d1);
        available.remove(d1);

        FireEvent event = new FireEvent("10:00", 1, "Fire", "Low", 0, 0);

        scheduler.droneFinished(event, d1);

        assertTrue(available.contains(d1));
        assertFalse(busy.contains(d1));
    }

    @Test
    public void testOnlyOneDroneAssigned() throws InterruptedException {
        FireEvent event = new FireEvent("10:00", 2, "Fire", "Moderate", 0, 0);

        scheduler.submitEvent(event);
        scheduler.assignEventToPreferredDrone();

        assertEquals(1, busy.size());
        assertEquals(2, available.size());
    }
}

