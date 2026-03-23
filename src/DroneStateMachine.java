/**
 * ============================================================
 * DroneStateMachine - State Pattern
 * ============================================================
 *
 * Keeps the current state object.
 * Receives events from the controller (DroneSubsystem).
 * Performs transitions.
 *
 * The state machine itself does NOT:
 * - send or receive UDP packets
 * - create threads
 * - read files
 */
enum DroneEvent {
    ASSIGNMENT_RECEIVED,
    TAKEOFF_DONE,
    ARRIVED_AT_ZONE,
    AGENT_EMPTY,
    FIRE_DONE,
    RETURNED_TO_BASE,
    REFILL_DONE
}


class DroneContext {
    final int droneId;

    DroneContext(int droneId) {
        this.droneId = droneId;
    }

    void log(String msg) {
        System.out.println("[Drone " + droneId + " FSM] " + msg);
    }
}

public class DroneStateMachine {

    private final DroneContext ctx;
    private State current = new IdleState();

    public DroneStateMachine(DroneContext ctx) {
        this.ctx = ctx;
    }

    public synchronized DroneStatus getState() {
        return current.name();
    }

    public synchronized void handleEvent(DroneEvent ev) {
        switch (ev) {
            case ASSIGNMENT_RECEIVED:
                current.onAssignmentReceived(this);
                break;
            case TAKEOFF_DONE:
                current.onTakeoffDone(this);
                break;
            case ARRIVED_AT_ZONE:
                current.onArrivedAtZone(this);
                break;
            case AGENT_EMPTY:
                current.onAgentEmpty(this);
                break;
            case FIRE_DONE:
                current.onFireDone(this);
                break;
            case RETURNED_TO_BASE:
                current.onReturnedToBase(this);
                break;
            case REFILL_DONE:
                current.onRefillDone(this);
                break;
        }
    }

    private void transitionTo(State next, DroneEvent event) {
        ctx.log(current.name() + " -> (Event: " + event + ") -> " + next.name());
        current = next;
    }

    private interface State {
        DroneStatus name();

        void onAssignmentReceived(DroneStateMachine fsm);

        void onTakeoffDone(DroneStateMachine fsm);

        void onArrivedAtZone(DroneStateMachine fsm);

        void onAgentEmpty(DroneStateMachine fsm);

        void onFireDone(DroneStateMachine fsm);

        void onReturnedToBase(DroneStateMachine fsm);

        void onRefillDone(DroneStateMachine fsm);
    }

    private class IdleState implements State {
        public DroneStatus name() { return DroneStatus.IDLE; }

        public void onAssignmentReceived(DroneStateMachine fsm) {
            fsm.transitionTo(new TakeoffState(), DroneEvent.ASSIGNMENT_RECEIVED);
        }

        public void onTakeoffDone(DroneStateMachine fsm) { ignore("TAKEOFF_DONE"); }
        public void onArrivedAtZone(DroneStateMachine fsm) { ignore("ARRIVED_AT_ZONE"); }
        public void onAgentEmpty(DroneStateMachine fsm) { ignore("AGENT_EMPTY"); }
        public void onFireDone(DroneStateMachine fsm) { ignore("FIRE_DONE"); }
        public void onReturnedToBase(DroneStateMachine fsm) { ignore("RETURNED_TO_BASE"); }
        public void onRefillDone(DroneStateMachine fsm) { ignore("REFILL_DONE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=IDLE)");
        }
    }

    private class TakeoffState implements State {
        public DroneStatus name() { return DroneStatus.TAKEOFF; }

        public void onTakeoffDone(DroneStateMachine fsm) {
            fsm.transitionTo(new EnRouteState(), DroneEvent.TAKEOFF_DONE);
        }

        public void onAssignmentReceived(DroneStateMachine fsm) { ignore("ASSIGNMENT_RECEIVED"); }
        public void onArrivedAtZone(DroneStateMachine fsm) { ignore("ARRIVED_AT_ZONE"); }
        public void onAgentEmpty(DroneStateMachine fsm) { ignore("AGENT_EMPTY"); }
        public void onFireDone(DroneStateMachine fsm) { ignore("FIRE_DONE"); }
        public void onReturnedToBase(DroneStateMachine fsm) { ignore("RETURNED_TO_BASE"); }
        public void onRefillDone(DroneStateMachine fsm) { ignore("REFILL_DONE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=TAKEOFF)");
        }
    }

    private class EnRouteState implements State {
        public DroneStatus name() { return DroneStatus.EN_ROUTE; }

        public void onArrivedAtZone(DroneStateMachine fsm) {
            fsm.transitionTo(new ExtinguishingState(), DroneEvent.ARRIVED_AT_ZONE);
        }

        public void onAssignmentReceived(DroneStateMachine fsm) { ignore("ASSIGNMENT_RECEIVED"); }
        public void onTakeoffDone(DroneStateMachine fsm) { ignore("TAKEOFF_DONE"); }
        public void onAgentEmpty(DroneStateMachine fsm) { ignore("AGENT_EMPTY"); }
        public void onFireDone(DroneStateMachine fsm) { ignore("FIRE_DONE"); }
        public void onReturnedToBase(DroneStateMachine fsm) { ignore("RETURNED_TO_BASE"); }
        public void onRefillDone(DroneStateMachine fsm) { ignore("REFILL_DONE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=EN_ROUTE)");
        }
    }

    private class ExtinguishingState implements State {
        public DroneStatus name() { return DroneStatus.EXTINGUISHING; }

        public void onAgentEmpty(DroneStateMachine fsm) {
            fsm.transitionTo(new ReturningState(), DroneEvent.AGENT_EMPTY);
        }

        public void onFireDone(DroneStateMachine fsm) {
            fsm.transitionTo(new ReturningState(), DroneEvent.FIRE_DONE);
        }

        public void onAssignmentReceived(DroneStateMachine fsm) { ignore("ASSIGNMENT_RECEIVED"); }
        public void onTakeoffDone(DroneStateMachine fsm) { ignore("TAKEOFF_DONE"); }
        public void onArrivedAtZone(DroneStateMachine fsm) { ignore("ARRIVED_AT_ZONE"); }
        public void onReturnedToBase(DroneStateMachine fsm) { ignore("RETURNED_TO_BASE"); }
        public void onRefillDone(DroneStateMachine fsm) { ignore("REFILL_DONE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=EXTINGUISHING)");
        }
    }

    private class ReturningState implements State {
        public DroneStatus name() { return DroneStatus.RETURNING; }

        public void onReturnedToBase(DroneStateMachine fsm) {
            fsm.transitionTo(new RefilledState(), DroneEvent.RETURNED_TO_BASE);
        }

        public void onAssignmentReceived(DroneStateMachine fsm) { ignore("ASSIGNMENT_RECEIVED"); }
        public void onTakeoffDone(DroneStateMachine fsm) { ignore("TAKEOFF_DONE"); }
        public void onArrivedAtZone(DroneStateMachine fsm) { ignore("ARRIVED_AT_ZONE"); }
        public void onAgentEmpty(DroneStateMachine fsm) { ignore("AGENT_EMPTY"); }
        public void onFireDone(DroneStateMachine fsm) { ignore("FIRE_DONE"); }
        public void onRefillDone(DroneStateMachine fsm) { ignore("REFILL_DONE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=RETURNING)");
        }
    }

    private class RefilledState implements State {
        public DroneStatus name() { return DroneStatus.REFILLED; }

        public void onRefillDone(DroneStateMachine fsm) {
            fsm.transitionTo(new IdleState(), DroneEvent.REFILL_DONE);
        }

        public void onAssignmentReceived(DroneStateMachine fsm) { ignore("ASSIGNMENT_RECEIVED"); }
        public void onTakeoffDone(DroneStateMachine fsm) { ignore("TAKEOFF_DONE"); }
        public void onArrivedAtZone(DroneStateMachine fsm) { ignore("ARRIVED_AT_ZONE"); }
        public void onAgentEmpty(DroneStateMachine fsm) { ignore("AGENT_EMPTY"); }
        public void onFireDone(DroneStateMachine fsm) { ignore("FIRE_DONE"); }
        public void onReturnedToBase(DroneStateMachine fsm) { ignore("RETURNED_TO_BASE"); }

        private void ignore(String ev) {
            ctx.log("Ignored " + ev + " (state=REFILLED)");
        }
    }
}