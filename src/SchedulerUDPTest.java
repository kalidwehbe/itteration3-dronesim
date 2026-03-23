/**
import org.junit.Before;
import org.junit.Test;

import java.awt.Point;

import static org.junit.Assert.*;

public class SchedulerUDPTest {

    private SchedulerUDP scheduler;

    @Before
    public void setup() throws Exception {
        scheduler = new SchedulerUDP(0); // random port
    }

    @Test
    public void testChooseClosestEvent() {

        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        drone.position = new Point(0,0);

        FireEvent e1 = new FireEvent("10:00",1,"FIRE","HIGH",50,50);
        FireEvent e2 = new FireEvent("10:01",2,"FIRE","LOW",5,5);

        scheduler.pendingEvents.add(e1);
        scheduler.pendingEvents.add(e2);

        FireEvent chosen = scheduler.chooseEventFor(drone);

        assertEquals(2, chosen.zoneId);
    }

    @Test
    public void testChooseEventEmptyQueue() {

        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        drone.position = new Point(0,0);

        FireEvent chosen = scheduler.chooseEventFor(drone);

        assertNull(chosen);
    }

    @Test
    public void testDronePositionUpdate() {

        scheduler.handleDronePos("DRONE_POS,1,10,20");

        SchedulerUDP.DroneInfo drone = scheduler.drones.get(1);

        assertEquals(10, drone.position.x);
        assertEquals(20, drone.position.y);
    }

    @Test
    public void testDroneWorkloadIncrease() {

        SchedulerUDP.DroneInfo drone = new SchedulerUDP.DroneInfo(1);
        scheduler.drones.put(1, drone);

        scheduler.handleDroneComplete("DRONE_COMPLETE,1,3");

        assertEquals(1, drone.workload);
    }
}
 */