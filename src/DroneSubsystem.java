import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;

public class DroneSubsystem {

    private final int id;
    private final InetAddress schedulerAddress;
    private final int schedulerPort;

    DatagramSocket socket;
    private final DroneStateMachine fsm;

    private int droneX = 0;
    private int droneY = 0;
    private int agent = 14;
    double battery = 100.0;

    // battery constants
    private static final double BATTERY_SAFETY_TOLERANCE = 5.0;
    private static final int UNITS_TRAVELLED_TO_DRAIN_1_PERCENT_BATTERY = 100;       // 1% per 100 units travelled
    static final int BATTERY_DRAIN_PER_EXTINGUISH_TICK = 1; // 2% per extinguishing tick
    private static final int BATTERY_DRAIN_PER_HOVER_SECOND = 1;     // 1% per second hover

    // fault handling
    private volatile boolean hardFaulted = false;

    private volatile boolean shutdownRequested = false;
    private volatile boolean returnRequested = false;

    public DroneSubsystem(int id, String host, int port) throws Exception {
        this.id = id;
        this.schedulerAddress = InetAddress.getByName(host);
        this.schedulerPort = port;
        this.fsm = new DroneStateMachine(new DroneContext(id));
    }

    public void start() {
        try {
            socket = new DatagramSocket(); // ephemeral port
            //System.out.println("[Drone " + id + "] started on local port " + socket.getLocalPort());
            EventLogger.log("DRONE", "STARTED",
                    "drone=" + id + " localPort=" + socket.getLocalPort());

            while (true) {
                if (hardFaulted) {
                    sendStatus(DroneStatus.FAULTED);
                    EventLogger.log("DRONE", "HARD_FAULT_SHUTDOWN",
                            "drone=" + id + " reason=nozzle_or_door_fault");
                    break;
                }

                if (shutdownRequested) {
                    EventLogger.log("DRONE", "EXITING",
                            "drone=" + id + " reason=shutdown_requested");
                    break;
                }

                if (fsm.getState() == DroneStatus.IDLE) {
                    sendReady();
                    FireEvent event = waitForAssignment();

                    if (shutdownRequested) {
                        EventLogger.log("DRONE", "EXITING",
                                "drone=" + id + " reason=shutdown_requested");
                        break;
                    }

                    if (returnRequested) {
                        returnRequested = false;
                        returnToBaseNow();
                        continue;
                    }

                    if (event == null) {
                        EventLogger.log("DRONE", "NO_TASK_RECEIVED",
                                "drone=" + id + " state=" + fsm.getState());
                        Thread.sleep(500);
                        continue;
                    }

                    EventLogger.log("DRONE", "ASSIGNMENT_ACCEPTED",
                            "drone=" + id + " zone=" + event.zoneId +
                                    " severity=" + event.severity +
                                    " x=" + event.centerX + " y=" + event.centerY +
                                    " faultType=" + event.faultType);
                    fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
                    performMission(event);
                } else {
                    Thread.sleep(100);
                }
            }

            EventLogger.log("DRONE", "PROCESS_TERMINATED",
                    "drone=" + id);

        } catch (Exception e) {
            EventLogger.log("DRONE", "ERROR",
                    "drone=" + id + " message=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private FireEvent waitForAssignment() throws Exception {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        String msg = new String(packet.getData(), 0, packet.getLength());
        EventLogger.log("DRONE", "MESSAGE_RECEIVED",
                "drone=" + id + " msg=" + msg);

        if (msg.equals("NO_TASK")) {
            return null;
        }

        if (msg.equals("SHUTDOWN")) {
            shutdownRequested = true;
            EventLogger.log("DRONE", "SHUTDOWN_REQUEST_RECEIVED",
                    "drone=" + id);
            return null;
        }

        if (msg.equals("RETURN")) {
            returnRequested = true;
            EventLogger.log("DRONE", "RETURN_REQUEST_RECEIVED",
                    "drone=" + id);
            return null;
        }

        if (!msg.startsWith("ASSIGN,")) {
            EventLogger.log("DRONE", "UNEXPECTED_MESSAGE",
                    "drone=" + id + " msg=" + msg);
            return null;
        }

        String[] p = msg.split(",");
        String time = p[1];
        int zoneId = Integer.parseInt(p[2]);
        String type = p[3];
        String severity = p[4];
        int x = Integer.parseInt(p[5]);
        int y = Integer.parseInt(p[6]);

        FaultType faultType = (p.length >= 8) ? FaultType.fromString(p[7]) : FaultType.NONE;

        EventLogger.log("DRONE", "ASSIGNMENT_RECEIVED",
                "drone=" + id + " time=" + time + " zone=" + zoneId +
                        " type=" + type + " severity=" + severity +
                        " x=" + x + " y=" + y +
                        " faultType=" + faultType);

        return new FireEvent(time, zoneId, type, severity, x, y, faultType);
    }

    private void performMission(FireEvent event) throws Exception {
        int requiredAgent = litresFor(event.severity);

        EventLogger.log("DRONE", "MISSION_STARTED",
                "drone=" + id + " zone=" + event.zoneId +
                        " severity=" + event.severity +
                        " requiredAgent=" + requiredAgent +
                        " faultType=" + event.faultType);

        while (requiredAgent > 0) {
            // fault handling
            if (event.faultType == FaultType.CORRUPTED_MESSAGE) {
                EventLogger.log("DRONE", "FAULT_TRIGGERED_CORRUPTED_MESSAGE",
                        "drone=" + id + " zone=" + event.zoneId);
                sendCorruptedMessage(event.zoneId);
                return;
            }

            // Make sure FSM is in correct state before takeoff
            if (fsm.getState() == DroneStatus.IDLE) {
                fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
            }

            sendStatus(DroneStatus.TAKEOFF);
            EventLogger.log("DRONE", "TAKEOFF_STARTED",
                    "drone=" + id + " zone=" + event.zoneId);
            Thread.sleep(500);
            fsm.handleEvent(DroneEvent.TAKEOFF_DONE);

            sendStatus(DroneStatus.EN_ROUTE);
            EventLogger.log("DRONE", "EN_ROUTE_STARTED",
                    "drone=" + id + " zone=" + event.zoneId +
                            " targetX=" + event.centerX + " targetY=" + event.centerY);

            boolean moveCompleted = moveTo(event.centerX, event.centerY, event);

            while (!moveCompleted){
                // mission aborted due to low battery
                moveTo(0, 0, event);
                agent = 14;
                battery = 100.0;
                sendStatus(DroneStatus.REFILLED);
                fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);
                fsm.handleEvent(DroneEvent.REFILL_DONE);

                fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
                
                // retry mission
                sendStatus(DroneStatus.TAKEOFF);
                EventLogger.log("DRONE", "TAKEOFF_STARTED",
                        "drone=" + id + " zone=" + event.zoneId);
                Thread.sleep(500);
                fsm.handleEvent(DroneEvent.TAKEOFF_DONE);

                sendStatus(DroneStatus.EN_ROUTE);
                EventLogger.log("DRONE", "EN_ROUTE_STARTED",
                        "drone=" + id + " zone=" + event.zoneId +
                                " targetX=" + event.centerX + " targetY=" + event.centerY);
                
                moveCompleted = moveTo(event.centerX, event.centerY, event);
            }

            sendArrival(event.zoneId);
            fsm.handleEvent(DroneEvent.ARRIVED_AT_ZONE);

            sendStatus(DroneStatus.EXTINGUISHING);
            EventLogger.log("DRONE", "EXTINGUISHING_STARTED",
                    "drone=" + id + " zone=" + event.zoneId +
                            " remainingRequired=" + requiredAgent +
                            " onboardAgent=" + agent);

            if (event.faultType == FaultType.NOZZLE_FAULT) {
                EventLogger.log("DRONE", "FAULT_TRIGGERED_NOZZLE_FAULT",
                        "drone=" + id + " zone=" + event.zoneId);
                handleNozzleFault(event.zoneId);
                return;
            }

            while (requiredAgent > 0 && agent > 0) {
                double batteryNeededToReturn = getBatteryNeededToReturn();

                if (battery - BATTERY_DRAIN_PER_EXTINGUISH_TICK < batteryNeededToReturn){
                    EventLogger.log("DRONE", "LOW_BATTERY_ABORT_MISSION",
                        "drone=" + id + " battery=" + String.format("%.2f", battery) + 
                        " pending_drain%=" + BATTERY_DRAIN_PER_EXTINGUISH_TICK +
                        " would leave " + String.format("%.2f", (battery - BATTERY_DRAIN_PER_EXTINGUISH_TICK)) + 
                        " need " + String.format("%.2f", batteryNeededToReturn) + " to return"
                    );

                    fsm.handleEvent(DroneEvent.BATTERY_LOW);
                    break;
                }

                agent--;
                requiredAgent--;

                battery = Math.max(0, battery - BATTERY_DRAIN_PER_EXTINGUISH_TICK);
                EventLogger.log("DRONE", "EXTINGUISHING_TICK_BATTERY_DRAIN",
                        "drone=" + id + " drain%=" + BATTERY_DRAIN_PER_EXTINGUISH_TICK + " battery%=" + String.format("%.2f", battery));

                sendStatus(DroneStatus.EXTINGUISHING);
                Thread.sleep(200);
            }

            if (requiredAgent == 0) {
                sendCompletion(event.zoneId);

                EventLogger.log("DRONE", "EXTINGUISHING_COMPLETE",
                        "drone=" + id + " zone=" + event.zoneId +
                                " remainingRequired=" + requiredAgent +
                                " onboardAgent=" + agent);

                fsm.handleEvent(DroneEvent.FIRE_DONE);
            } else {
                EventLogger.log("DRONE", "AGENT_EMPTY",
                        "drone=" + id + " zone=" + event.zoneId +
                                " remainingRequired=" + requiredAgent);

                fsm.handleEvent(DroneEvent.AGENT_EMPTY);
            }

            sendStatus(DroneStatus.RETURNING);
            EventLogger.log("DRONE", "RETURNING_TO_BASE",
                    "drone=" + id + " zone=" + event.zoneId);
            moveTo(0, 0, event);
            fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);

            agent = 14;
            battery = 100.0;
            sendStatus(DroneStatus.REFILLED);
            //sendStatus(DroneStatus.REFILLED);
            EventLogger.log("DRONE", "REFILL_COMPLETE",
                    "drone=" + id + " agent=" + agent + " battery%=" + String.format("%.2f", battery));
            fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);
            fsm.handleEvent(DroneEvent.REFILL_DONE);
            sendStatus(DroneStatus.IDLE);
        }
        EventLogger.log("DRONE", "MISSION_FINISHED",
                "drone=" + id + " zone=" + event.zoneId);
    }

    public DroneStatus getState() {
        return fsm.getState();
    }

    private int litresFor(String severity) {
        if (severity.equalsIgnoreCase("High")) return 30;
        if (severity.equalsIgnoreCase("Moderate")) return 20;
        return 10;
    }

    boolean moveTo(int targetX, int targetY, FireEvent event) throws Exception {
        int startX = droneX;
        int startY = droneY;
        int steps = 20;

        boolean softFaultInjected = false;
        int faultStep = -1;

        // Only generate a random mid-flight step if the drone has STUCK_IN_FLIGHT fault
        if (event.faultType == FaultType.STUCK_IN_FLIGHT) {
            faultStep = new Random().nextInt(steps / 2, steps - 1); // middle to near end
        }

        EventLogger.log("DRONE", "MOVE_STARTED",
                "drone=" + id + " fromX=" + startX + " fromY=" + startY +
                        " toX=" + targetX + " toY=" + targetY);

        for (int i = 1; i <= steps; i++) {
            int x = startX + (targetX - startX) * i / steps;
            int y = startY + (targetY - startY) * i / steps;

            // calculates the distance traveled in the current step
            int lastX = (i == 1) ? startX : (startX + (targetX - startX) * (i - 1) / steps);
            int lastY = (i == 1) ? startY : (startY + (targetY - startY) * (i - 1) / steps);
            int stepDistance = (int) Math.sqrt(Math.pow(x - lastX, 2) + Math.pow(y - lastY, 2));

            // drains battery - 1% per 100 units
            double drain = (double) stepDistance / UNITS_TRAVELLED_TO_DRAIN_1_PERCENT_BATTERY;
            
            // calculates how much battery would be needed to return from the new position
            double newDistanceToBase = Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2));
            double batteryNeededToReturn = (newDistanceToBase / UNITS_TRAVELLED_TO_DRAIN_1_PERCENT_BATTERY) + BATTERY_SAFETY_TOLERANCE;
            
            // if the drone wouldn't have enough battery to return after new step
            if (battery - drain < batteryNeededToReturn && (targetX != 0 || targetY != 0)) {
                EventLogger.log("DRONE", "LOW_BATTERY_ABORT_MISSION",
                        "drone=" + id + " battery%=" + String.format("%.2f", battery) + 
                        " pending_drain%=" + drain +
                        " would leave " + String.format("%.2f", (battery - drain)) + 
                        " need " + String.format("%.2f", batteryNeededToReturn) + "% to return");
               
                fsm.handleEvent(DroneEvent.BATTERY_LOW);
                return false;  // Abort mission, return to base
            }
            
            battery = Math.max(0, battery - drain);

            sendStatus(fsm.getState());

            EventLogger.log("DRONE", "MOVE_STEP_BATTERY_DRAIN",
                    "drone=" + id + " step=" + i + " distance=" + stepDistance + " drain%=" + String.format("%.2f", drain) + " battery%=" + String.format("%.2f", battery));

            // Inject soft fault only if this drone has it
            if (!softFaultInjected && faultStep == i) {
                softFaultInjected = true;
                EventLogger.log("DRONE", "SOFT_FAULT_TRIGGERED",
                        "drone=" + id + " at step " + i + " x=" + x + " y=" + y);
                sendStatus(DroneStatus.SOFT_FAULTED);

                // Hover for 3 seconds with battery drain each second
                for (int second = 0; second < 3; second++) {
                    Thread.sleep(1000);
                    battery = Math.max(0, battery - BATTERY_DRAIN_PER_HOVER_SECOND);
                    EventLogger.log("DRONE", "HOVER_SEC_BATTERY_DRAIN",
                            "drone=" + id + " drain%=" + BATTERY_DRAIN_PER_HOVER_SECOND + " battery%=" + String.format("%.2f", battery));
                    sendStatus(DroneStatus.SOFT_FAULTED);
                }

                sendStatus(DroneStatus.EN_ROUTE);
                event.faultType = FaultType.NONE;
                EventLogger.log("DRONE", "SOFT_FAULT_CLEARED",
                        "drone=" + id + " resumes flight");
            }

            sendPosition(x, y);
            Thread.sleep(100);
        }

        EventLogger.log("DRONE", "MOVE_COMPLETED",
                "drone=" + id + " x=" + droneX + " y=" + droneY);
        return true;
    }

    private void handleNozzleFault(int zoneId) throws Exception {
        EventLogger.log("DRONE", "FAULT_INJECTED_NOZZLE_FAULT",
                "drone=" + id + " zone=" + zoneId);
        hardFaulted = true;
        sendStatus(DroneStatus.FAULTED);
    }

    private void sendCorruptedMessage(int zoneId) throws Exception {
        send("DRONE_STATUS_CORRUPTED," + id + ",BROKEN_PAYLOAD," + zoneId);
        EventLogger.log("DRONE", "FAULT_INJECTED_CORRUPTED_MESSAGE",
                "drone=" + id + " zone=" + zoneId);
        hardFaulted = true;
        sendStatus(DroneStatus.FAULTED);
    }

    private void sendReady() throws Exception {
        send("READY," + id + "," + droneX + "," + droneY + "," + agent + "," + String.format("%.2f", battery));
        //System.out.println("[Drone " + id + "] READY");
        EventLogger.log("DRONE", "READY_SENT",
                "drone=" + id + " x=" + droneX + " y=" + droneY + " agent=" + agent + " battery%=" + String.format("%.2f", battery));
    }

    private void sendStatus(DroneStatus status) throws Exception {
        send("DRONE_STATUS," + id + "," + status + "," + agent + "," + String.format("%.2f", battery));
        //System.out.println("[Drone " + id + "] Status: " + status + " Agent: " + agent);
        EventLogger.log("DRONE", "STATUS_SENT",
                "drone=" + id + " status=" + status + " agent=" + agent + " battery%=" + String.format("%.2f", battery));
    }

    private void sendPosition(int x, int y) throws Exception {
        droneX = x;
        droneY = y;
        send("DRONE_POS," + id + "," + x + "," + y);
        EventLogger.log("DRONE", "POSITION_SENT",
                "drone=" + id + " x=" + x + " y=" + y);
    }

    private void sendArrival(int zoneId) throws Exception {
        send("DRONE_ARRIVED," + id + "," + zoneId);
        //System.out.println("[Drone " + id + "] Arrived at zone: " + zoneId);
        EventLogger.log("DRONE", "ARRIVAL_SENT",
                "drone=" + id + " zone=" + zoneId);
    }

    private void sendCompletion(int zoneId) throws Exception {
        send("DRONE_COMPLETE," + id + "," + zoneId);
        //System.out.println("[Drone " + id + "] Completed zone: " + zoneId);
        EventLogger.log("DRONE", "COMPLETION_SENT",
                "drone=" + id + " zone=" + zoneId);
    }

    private void send(String msg) throws Exception {
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, schedulerAddress, schedulerPort);
        socket.send(packet);
    }

    private void returnToBaseNow() throws Exception {
        if (droneX == 0 && droneY == 0) {
            sendStatus(DroneStatus.IDLE);
            EventLogger.log("DRONE", "ALREADY_AT_BASE",
                    "drone=" + id);
            return;
        }

        sendStatus(DroneStatus.RETURNING);
        EventLogger.log("DRONE", "FORCED_RETURN_TO_BASE",
                "drone=" + id + " fromX=" + droneX + " fromY=" + droneY);

        FireEvent dummy = new FireEvent("00:00:00", 0, "RETURN", "Low", 0, 0, FaultType.NONE);
        moveTo(0, 0, dummy);

        agent = 14;
        battery = 100.0;
        sendStatus(DroneStatus.REFILLED);
        fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);
        fsm.handleEvent(DroneEvent.REFILL_DONE);

        sendStatus(DroneStatus.IDLE);

        EventLogger.log("DRONE", "RETURN_TO_BASE_COMPLETE",
                "drone=" + id + " x=" + droneX + " y=" + droneY + " agent=" + agent);
    }

    double getBatteryNeededToReturn() {
        double distanceToBase = Math.sqrt(Math.pow(droneX, 2) + Math.pow(droneY, 2));
        return (distanceToBase / UNITS_TRAVELLED_TO_DRAIN_1_PERCENT_BATTERY) + BATTERY_SAFETY_TOLERANCE;
    }

    public static void main(String[] args) throws Exception {
        int id = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        new DroneSubsystem(id, "localhost", 7000).start();
    }
}