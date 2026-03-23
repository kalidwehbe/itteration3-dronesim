import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DroneSubsystemTest {

    private DroneSubsystem drone;

    @Before
    public void setup() throws Exception {
        drone = new DroneSubsystem(1, "localhost", 7000);
    }

    @Test
    public void testInitialState() {
        assertEquals(DroneStatus.IDLE, drone.getState());
    }

    @Test
    public void testAssignmentTriggersTakeoff() {

        DroneStateMachine fsm =
                new DroneStateMachine(new DroneContext(1));

        fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);

        assertEquals(DroneStatus.TAKEOFF, fsm.getState());
    }

    @Test
    public void testMissionSequence() {

        DroneStateMachine fsm =
                new DroneStateMachine(new DroneContext(1));

        fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
        fsm.handleEvent(DroneEvent.TAKEOFF_DONE);
        fsm.handleEvent(DroneEvent.ARRIVED_AT_ZONE);
        fsm.handleEvent(DroneEvent.FIRE_DONE);
        fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);
        fsm.handleEvent(DroneEvent.REFILL_DONE);

        assertEquals(DroneStatus.IDLE, fsm.getState());
    }

    @Test
    public void testAgentEmptyPath() {

        DroneStateMachine fsm =
                new DroneStateMachine(new DroneContext(1));

        fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
        fsm.handleEvent(DroneEvent.TAKEOFF_DONE);
        fsm.handleEvent(DroneEvent.ARRIVED_AT_ZONE);
        fsm.handleEvent(DroneEvent.AGENT_EMPTY);

        assertEquals(DroneStatus.RETURNING, fsm.getState());
    }
}