import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class DroneSubsystem {

    private final int id;
    private final InetAddress schedulerAddress;
    private final int schedulerPort;

    private DatagramSocket socket;
    private final DroneStateMachine fsm;

    private int droneX = 0;
    private int droneY = 0;
    private int agent = 14;


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
                if (fsm.getState() == DroneStatus.IDLE) {
                    sendReady();
                    FireEvent event = waitForAssignment();

                    if (event == null) {
                        EventLogger.log("DRONE", "NO_TASK_RECEIVED",
                                "drone=" + id + " state=" + fsm.getState());
                        Thread.sleep(500);
                        continue;
                    }

                    EventLogger.log("DRONE", "ASSIGNMENT_ACCEPTED",
                            "drone=" + id + " zone=" + event.zoneId +
                                    " severity=" + event.severity +
                                    " x=" + event.centerX + " y=" + event.centerY);
                    fsm.handleEvent(DroneEvent.ASSIGNMENT_RECEIVED);
                    performMission(event);
                } else {
                    Thread.sleep(100);
                }
            }
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

        EventLogger.log("DRONE", "ASSIGNMENT_RECEIVED",
                "drone=" + id + " time=" + time + " zone=" + zoneId +
                        " type=" + type + " severity=" + severity +
                        " x=" + x + " y=" + y);

        FaultType faultType = (p.length >= 8) ? FaultType.fromString(p[7]) : FaultType.NONE; //Parse the fault type from the message
        return new FireEvent(time, zoneId, type, severity, x, y, faultType);
    }

    private void performMission(FireEvent event) throws Exception {
        int requiredAgent = litresFor(event.severity);

        EventLogger.log("DRONE", "MISSION_STARTED",
                "drone=" + id + " zone=" + event.zoneId +
                        " severity=" + event.severity +
                        " requiredAgent=" + requiredAgent);

        while (requiredAgent > 0) {
            sendStatus(DroneStatus.TAKEOFF);
            EventLogger.log("DRONE", "TAKEOFF_STARTED",
                    "drone=" + id + " zone=" + event.zoneId);
            Thread.sleep(500);
            fsm.handleEvent(DroneEvent.TAKEOFF_DONE);

            sendStatus(DroneStatus.EN_ROUTE);
            EventLogger.log("DRONE", "EN_ROUTE_STARTED",
                    "drone=" + id + " zone=" + event.zoneId +
                            " targetX=" + event.centerX + " targetY=" + event.centerY);
            moveTo(event.centerX, event.centerY);
            sendArrival(event.zoneId);
            fsm.handleEvent(DroneEvent.ARRIVED_AT_ZONE);

            sendStatus(DroneStatus.EXTINGUISHING);
            EventLogger.log("DRONE", "EXTINGUISHING_STARTED",
                    "drone=" + id + " zone=" + event.zoneId +
                            " remainingRequired=" + requiredAgent +
                            " onboardAgent=" + agent);

            while (requiredAgent > 0 && agent > 0) {
                agent--;
                requiredAgent--;
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
            moveTo(0, 0);
            fsm.handleEvent(DroneEvent.RETURNED_TO_BASE);

            agent = 14;
            sendStatus(DroneStatus.REFILLED);
            //sendStatus(DroneStatus.REFILLED);
            EventLogger.log("DRONE", "REFILL_COMPLETE",
                    "drone=" + id + " agent=" + agent);
            fsm.handleEvent(DroneEvent.REFILL_DONE);
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

    private void moveTo(int targetX, int targetY) throws Exception {
        int startX = droneX;
        int startY = droneY;
        int steps = 20;

        EventLogger.log("DRONE", "MOVE_STARTED",
                "drone=" + id + " fromX=" + startX + " fromY=" + startY +
                        " toX=" + targetX + " toY=" + targetY);

        for (int i = 1; i <= steps; i++) {
            int x = startX + (targetX - startX) * i / steps;
            int y = startY + (targetY - startY) * i / steps;
            sendPosition(x, y);
            Thread.sleep(100);
        }
        EventLogger.log("DRONE", "MOVE_COMPLETED",
                "drone=" + id + " x=" + droneX + " y=" + droneY);
    }

    private void sendReady() throws Exception {
        send("READY," + id + "," + droneX + "," + droneY + "," + agent);
        //System.out.println("[Drone " + id + "] READY");
        EventLogger.log("DRONE", "READY_SENT",
                "drone=" + id + " x=" + droneX + " y=" + droneY + " agent=" + agent);
    }

    private void sendStatus(DroneStatus status) throws Exception {
        send("DRONE_STATUS," + id + "," + status + "," + agent);
        //System.out.println("[Drone " + id + "] Status: " + status + " Agent: " + agent);
        EventLogger.log("DRONE", "STATUS_SENT",
                "drone=" + id + " status=" + status + " agent=" + agent);
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

    public static void main(String[] args) throws Exception {
        int id = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        new DroneSubsystem(id, "localhost", 7000).start();
    }
}