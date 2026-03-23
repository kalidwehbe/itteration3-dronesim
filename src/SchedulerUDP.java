import java.awt.Point;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

public class SchedulerUDP {

    private final DatagramSocket socket;
    final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Map<Integer, FireZone> zones = new HashMap<>();
    final Queue<FireEvent> pendingEvents = new LinkedList<>();
    private final FireGUI gui;

    public SchedulerUDP(int port) throws Exception {
        socket = new DatagramSocket(port);
        gui = new FireGUI();
        EventLogger.log("SCHEDULER", "STARTED",
                "port=" + port);
    }

    public void start() throws Exception {
        //System.out.println("[Scheduler] Running on port " + socket.getLocalPort());
        EventLogger.log("SCHEDULER", "RUNNING",
                "localPort=" + socket.getLocalPort());

        byte[] buffer = new byte[1024];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String msg = new String(packet.getData(),0, packet.getLength());
            EventLogger.log("SCHEDULER", "MESSAGE_RECEIVED",
                    "from=" + packet.getAddress() + ":" + packet.getPort() +
                            " msg=" + msg);
            handleMessage(msg, packet.getAddress(), packet.getPort());
        }
    }

    private void handleMessage(String msg, InetAddress sender, int port) throws Exception {
        String[] parts = msg.split(",");
        String type = parts[0];

        switch (type) {
            case "ZONE":
                handleZone(msg);
                break;
            case "EVENT":
                handleEvent(msg);
                break;
            case "READY":
                handleReady(msg, sender, port);
                break;
            case "DRONE_STATUS":
                handleDroneStatus(msg);
                break;
            case "DRONE_POS":
                handleDronePos(msg);
                break;
            case "DRONE_ARRIVED":
                handleDroneArrived(msg);
                break;
            case "DRONE_COMPLETE":
                handleDroneComplete(msg);
                break;
            default:
                //System.out.println("[Scheduler] Unknown message: " + msg);
                EventLogger.log("SCHEDULER", "UNKNOWN_MESSAGE",
                        "msg=" + msg);
                break;
        }
    }

    private void handleZone(String msg) {
        String[] p = msg.split(",");
        int zoneId = Integer.parseInt(p[1]);
        int x1 = Integer.parseInt(p[2]);
        int y1 = Integer.parseInt(p[3]);
        int x2 = Integer.parseInt(p[4]);
        int y2 = Integer.parseInt(p[5]);

        zones.put(zoneId, new FireZone(zoneId, x1, y1, x2, y2));
        gui.setZones(zones);

        EventLogger.log("SCHEDULER", "ZONE_RECEIVED",
                "zone=" + zoneId + " x1=" + x1 + " y1=" + y1 +
                        " x2=" + x2 + " y2=" + y2);

    }

    private void handleEvent(String msg) {
        String[] p = msg.split(",");
        String time = p[1];
        int zoneId = Integer.parseInt(p[2]);
        String type = p[3];
        String severity = p[4];
        int x = Integer.parseInt(p[5]);
        int y = Integer.parseInt(p[6]);

        FaultType faultType = (p.length >= 8) ? FaultType.fromString(p[7]) : FaultType.NONE; // Parse the fault type from the message

        FireEvent event = new FireEvent(time, zoneId, type, severity, x, y, faultType);
        pendingEvents.add(event);

        gui.setZoneOnFire(zoneId, true, severity);
        //System.out.println("[Scheduler] New fire event: Zone " + zoneId + " Severity: " + severity);
        EventLogger.log("SCHEDULER", "EVENT_RECEIVED",
                "time=" + time + " zone=" + zoneId +
                        " type=" + type + " severity=" + severity +
                        " x=" + x + " y=" + y +
                        " pendingCount=" + pendingEvents.size());
    }

    private void handleReady(String msg, InetAddress sender, int port) throws Exception {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        int x = Integer.parseInt(p[2]);
        int y = Integer.parseInt(p[3]);
        int agent = Integer.parseInt(p[4]);

        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            drone = new DroneInfo(droneId);
            drones.put(droneId, drone);
            gui.registerDrone(droneId);

            EventLogger.log("SCHEDULER", "DRONE_REGISTERED",
                    "drone=" + droneId);
        }

        drone.address = sender;
        drone.port = port;
        drone.position = new Point(x, y);
        drone.agent = agent;
        drone.state = "IDLE";

        gui.updateDroneStatus(droneId, "IDLE");
        gui.updateAgent(droneId, agent);
        gui.updateDronePosition(droneId, x, y);

        EventLogger.log("SCHEDULER", "DRONE_READY_RECEIVED",
                "drone=" + droneId + " x=" + x + " y=" + y +
                        " agent=" + agent + " workload=" + drone.workload);

        FireEvent event = chooseEventFor(drone);

        if (event == null) {
            send("NO_TASK", sender, port);
            EventLogger.log("SCHEDULER", "NO_TASK_SENT",
                    "drone=" + droneId);
            return;
        }

        send("ASSIGN," + event.time + "," + event.zoneId + "," + event.type + "," + event.severity + "," + event.centerX + "," + event.centerY + "," + event.faultType.name(), sender, port);

        drone.state = "ASSIGNED";
        gui.updateZone(droneId, event.zoneId);

        pendingEvents.remove(event);

        //System.out.println("[Scheduler] Assigned zone " + event.zoneId + " to drone " + droneId);
        EventLogger.log("SCHEDULER", "DRONE_ASSIGNED",
                "drone=" + droneId + " zone=" + event.zoneId +
                        " severity=" + event.severity +
                        " eventTime=" + event.time +
                        " targetX=" + event.centerX + " targetY=" + event.centerY +
                        " pendingCount=" + pendingEvents.size());
    }

    public FireEvent chooseEventFor(DroneInfo drone) {
        FireEvent best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestWorkload = Integer.MAX_VALUE;

        for (FireEvent event : pendingEvents) {
            double distance = drone.position.distance(event.centerX, event.centerY);

            if (drone.workload < bestWorkload) {
                best = event;
                bestWorkload = drone.workload;
                bestDistance = distance;
            } else if (drone.workload == bestWorkload && distance < bestDistance) {
                best = event;
                bestDistance = distance;
            }
        }
        if (best != null){
            EventLogger.log("SCHEDULER", "EVENT_SELECTED_FOR_DRONE",
                    "drone=" + drone.id + " zone=" + best.zoneId +
                            " distance=" + bestDistance +
                            " workload=" + drone.workload);
            /**
            System.out.println("[Scheduler] Drone " + drone.id +
                    " selected for zone " + best.zoneId +
                    " (distance=" + bestDistance +
                    ", workload=" + drone.workload + ")");
             */
        }

        return best;
    }

    public void handleDroneStatus(String msg) {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        String state = p[2];
        int agent = Integer.parseInt(p[3]);

        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            drone = new DroneInfo(droneId);
            drones.put(droneId, drone);
            gui.registerDrone(droneId);

            EventLogger.log("SCHEDULER", "DRONE_REGISTERED_FROM_STATUS",
                    "drone=" + droneId);
        }

        drone.state = state;
        drone.agent = agent;

        gui.updateDroneStatus(droneId, state);
        gui.updateAgent(droneId, agent);

        EventLogger.log("SCHEDULER", "DRONE_STATUS_RECEIVED",
                "drone=" + droneId + " state=" + state + " agent=" + agent);
    }

    public void handleDronePos(String msg) {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        int x = Integer.parseInt(p[2]);
        int y = Integer.parseInt(p[3]);

        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            drone = new DroneInfo(droneId);
            drones.put(droneId, drone);
            gui.registerDrone(droneId);

            EventLogger.log("SCHEDULER", "DRONE_REGISTERED_FROM_POSITION",
                    "drone=" + droneId);
        }

        drone.position = new Point(x, y);
        gui.updateDronePosition(droneId, x, y);

        //System.out.println("[Scheduler] Drone " + droneId +" is at position "+ x+ " x " + y +" y");
        EventLogger.log("SCHEDULER", "DRONE_POSITION_RECEIVED",
                "drone=" + droneId + " x=" + x + " y=" + y);

    }

    private void handleDroneArrived(String msg) {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        int zoneId = Integer.parseInt(p[2]);

        //System.out.println("[Scheduler] Drone " + droneId + " arrived at zone " + zoneId);
        EventLogger.log("SCHEDULER", "DRONE_ARRIVED_RECEIVED",
                "drone=" + droneId + " zone=" + zoneId);
    }

    void handleDroneComplete(String msg) {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        int zoneId = Integer.parseInt(p[2]);

        DroneInfo drone = drones.get(droneId);
        if (drone != null) {
            drone.workload++;
            drone.state = "IDLE";
        }

        gui.setZoneOnFire(zoneId, false, null);
        gui.updateDroneStatus(droneId, "IDLE");

        //System.out.println("[Scheduler] Drone " + droneId + " completed zone " + zoneId);
        EventLogger.log("SCHEDULER", "DRONE_COMPLETE_RECEIVED",
                "drone=" + droneId + " zone=" + zoneId +
                        " newWorkload=" + (drone != null ? drone.workload : -1));
    }

    private void send(String msg, InetAddress addr, int port) throws Exception {
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
        socket.send(packet);
        EventLogger.log("SCHEDULER", "MESSAGE_SENT",
                "to=" + addr + ":" + port + " msg=" + msg);
    }

    public static class DroneInfo {
        final int id;
        InetAddress address;
        int port;
        String state = "IDLE";
        Point position = new Point(0, 0);
        int agent = 14;
        int workload = 0;

        DroneInfo(int id) {
            this.id = id;
        }
    }

    public static void main(String[] args) throws Exception {
        EventLogger.clearLog();
        new SchedulerUDP(7000).start();
    }
}
