import java.awt.Point;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

public class SchedulerUDP {

    // fault handling: configuration
    private static final long EN_ROUTE_WARNING_MS = 3500;
    private static final long EN_ROUTE_TIMEOUT_MS = 8000;
    private static final long EXTINGUISH_PROGRESS_TIMEOUT_MS = 3200;

    private final DatagramSocket socket;
    final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Map<Integer, FireZone> zones = new HashMap<>();
    final Queue<FireEvent> pendingEvents = new LinkedList<>();
    // Map of zoneId -> list of active events
    private final Map<Integer, List<FireEvent>> eventsPerZone = new HashMap<>();
    private final FireGUI gui;

    private volatile boolean noMoreEvents = false;
    private volatile boolean shutdownInitiated = false;
    private volatile boolean running = true;

    public SchedulerUDP(int port) throws Exception {
        socket = new DatagramSocket(port);
        gui = new FireGUI(this);
        EventLogger.log("SCHEDULER", "STARTED",
                "port=" + port);
    }

    public void start() throws Exception {
        PerformanceMetricsGenerator.clearMetricsFile("performance_metrics.txt");
        EventLogger.log("SCHEDULER", "RUNNING",
                "localPort=" + socket.getLocalPort());

        byte[] buffer = new byte[1024];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());
                EventLogger.log("SCHEDULER", "MESSAGE_RECEIVED",
                        "from=" + packet.getAddress() + ":" + packet.getPort() +
                                " msg=" + msg);

                try {
                    handleMessage(msg, packet.getAddress(), packet.getPort());
                    detectTimeoutFaults();
                } catch (Exception e) {
                    EventLogger.log("SCHEDULER", "CORRUPTED_OR_INVALID_MESSAGE",
                            "msg=" + msg + " error=" + e.getMessage());
                }

            } catch (Exception e) {
                if (!running || socket.isClosed()) {
                    EventLogger.log("SCHEDULER", "SOCKET_CLOSED",
                            "message=Scheduler socket closed during shutdown");
                    break;
                }
                throw e;
            }
        }

        EventLogger.log("SCHEDULER", "PROCESS_TERMINATED",
                "message=Scheduler main loop exited cleanly");

        try {
            // small delay so the drones can finish writing final log lines
            Thread.sleep(1000);

            PerformanceMetricsGenerator.generateFromEventLog(
                    "event_log.txt",
                    "performance_metrics.txt"
            );

            EventLogger.log("SCHEDULER", "METRICS_FILE_CREATED",
                    "file=performance_metrics.txt");
        } catch (Exception e) {
            EventLogger.log("SCHEDULER", "METRICS_FILE_FAILED",
                    "error=" + e.getMessage());
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
            case "NO_MORE_EVENTS":
                handleNoMoreEvents();
                break;
            default:
                //System.out.println("[Scheduler] Unknown message: " + msg);
                EventLogger.log("SCHEDULER", "UNKNOWN_MESSAGE",
                        "msg=" + msg);
                break;
        }
    }

    private void handleNoMoreEvents() {
        noMoreEvents = true;
        EventLogger.log("SCHEDULER", "NO_MORE_EVENTS_RECEIVED",
                "pendingEvents=" + pendingEvents.size());

        checkForSystemShutdown();
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

        FaultType faultType = (p.length >= 8) ? FaultType.fromString(p[7]) : FaultType.NONE;

        FireEvent event = new FireEvent(time, zoneId, type, severity, x, y, faultType);
        pendingEvents.add(event);

        // Add to per-zone list
        eventsPerZone.putIfAbsent(zoneId, new ArrayList<>());
        eventsPerZone.get(zoneId).add(event);
        printEventsPerZone();

        // GUI: always display the "most severe" or first event
        gui.setZoneOnFire(zoneId, true, severity);

        EventLogger.log("SCHEDULER", "EVENT_RECEIVED",
                "time=" + time + " zone=" + zoneId +
                        " type=" + type + " severity=" + severity +
                        " x=" + x + " y=" + y +
                        " faultType=" + faultType +
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

        EventLogger.log("SCHEDULER", "DRONE_READY_RECEIVED",
                "drone=" + droneId + " x=" + x + " y=" + y +
                        " agent=" + agent + " offline=" + drone.offline);

        if (drone.offline) {
            EventLogger.log("SCHEDULER", "READY_IGNORED_OFFLINE_DRONE",
                    "drone=" + droneId);
            return;
        }

        if (shutdownInitiated) {
            send("SHUTDOWN", sender, port);
            EventLogger.log("SCHEDULER", "SHUTDOWN_SENT_ON_READY",
                    "drone=" + droneId);
            return;
        }

        drone.address = sender;
        drone.port = port;
        drone.position = new Point(x, y);
        drone.agent = agent;
        drone.state = "IDLE";

        FireEvent event;


        synchronized (pendingEvents) {
            event = chooseEventFor(drone);

            if (event != null) {
                pendingEvents.remove(event); // remove BEFORE sending
            }
        }

        if (event == null) {
            checkForSystemShutdown();

            if (shutdownInitiated) {
                send("SHUTDOWN", sender, port);
                EventLogger.log("SCHEDULER", "SHUTDOWN_SENT_ON_READY",
                        "drone=" + droneId);
                return;
            }

            EventLogger.log("SCHEDULER", "NO_TASK_SENT",
                    "drone=" + droneId);
            send("NO_TASK", sender, port);
            return;
        }

        send("ASSIGN," + event.time + "," + event.zoneId + "," +
                event.type + "," + event.severity + "," +
                event.centerX + "," + event.centerY + "," +
                event.faultType.name(), sender, port);

        EventLogger.log("SCHEDULER", "DRONE_ASSIGNED",
                "drone=" + droneId + " zone=" + event.zoneId +
                        " severity=" + event.severity +
                        " faultType=" + event.faultType +
                        " eventTime=" + event.time +
                        " targetX=" + event.centerX + " targetY=" + event.centerY);

        drone.state = "ASSIGNED";
        drone.assignedEvent = event;
        drone.assignedAtMs = System.currentTimeMillis();

        gui.updateZone(droneId, event.zoneId);
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
        // fault handling: malformed packet detection
        if (p.length < 4) {
            throw new IllegalArgumentException("DRONE_STATUS malformed: " + msg);
        }
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
        drone.lastStatusAtMs = System.currentTimeMillis();

        gui.updateDroneStatus(droneId, state);
        gui.updateAgent(droneId, agent);

        // fault handling: state-progress tracking and hard-fault reaction
        if ("EN_ROUTE".equalsIgnoreCase(state)) {
            if (drone.assignedAtMs == 0) {
                drone.assignedAtMs = System.currentTimeMillis();
            }
        } else if ("EXTINGUISHING".equalsIgnoreCase(state)) {
            drone.lastExtinguishProgressAtMs = System.currentTimeMillis();
            
            if (agent < drone.lastExtinguishAgent) {
                // drone.lastExtinguishProgressAtMs = System.currentTimeMillis();
                drone.lastExtinguishAgent = agent;
            }
        } else if ("FAULTED".equalsIgnoreCase(state)) {
            markDroneFaultAndRequeue(drone, "HARD_FAULT:NOZZLE_FAULT");
        }

        EventLogger.log("SCHEDULER", "DRONE_STATUS_RECEIVED",
                "drone=" + droneId + " state=" + state + " agent=" + agent);
        checkForSystemShutdown();
    }

    public void handleDronePos(String msg) {
        String[] p = msg.split(",");
        // fault handling: malformed packet detection
        if (p.length < 4) {
            throw new IllegalArgumentException("DRONE_POS malformed: " + msg);
        }
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
        drone.lastPositionAtMs = System.currentTimeMillis();
        gui.updateDronePosition(droneId, x, y);

        //System.out.println("[Scheduler] Drone " + droneId +" is at position "+ x+ " x " + y +" y");
        EventLogger.log("SCHEDULER", "DRONE_POSITION_RECEIVED",
                "drone=" + droneId + " x=" + x + " y=" + y);
        checkForSystemShutdown();
    }

    private void handleDroneArrived(String msg) {
        String[] p = msg.split(",");
        // fault handling: malformed packet detection
        if (p.length < 3) {
            throw new IllegalArgumentException("DRONE_ARRIVED malformed: " + msg);
        }
        int droneId = Integer.parseInt(p[1]);
        int zoneId = Integer.parseInt(p[2]);

        DroneInfo drone = drones.get(droneId);
        if (drone != null) {
            drone.lastArrivedAtMs = System.currentTimeMillis();
            drone.hasArrivedForCurrentMission = true;
            drone.warningLogged = false;
        }

        //System.out.println("[Scheduler] Drone " + droneId + " arrived at zone " + zoneId);
        EventLogger.log("SCHEDULER", "DRONE_ARRIVED_RECEIVED",
                "drone=" + droneId + " zone=" + zoneId);
    }

    private void printEventsPerZone() {
        System.out.println("=== Events Per Zone ===");
        for (Map.Entry<Integer, List<FireEvent>> entry : eventsPerZone.entrySet()) {
            List<String> severities = new ArrayList<>();
            for (FireEvent e : entry.getValue()) {
                severities.add(e.severity);
            }
            System.out.println("Zone " + entry.getKey() + " -> " + severities);
        }
        System.out.println("=======================");
    }

    void handleDroneComplete(String msg) {
        String[] p = msg.split(",");
        int droneId = Integer.parseInt(p[1]);
        int zoneId = Integer.parseInt(p[2]);

        DroneInfo drone = drones.get(droneId);
        if (drone != null) {
            FireEvent completed = drone.assignedEvent;

            drone.workload++;
            drone.state = "IDLE";
            drone.assignedEvent = null;
            drone.assignedAtMs = 0;
            drone.lastArrivedAtMs = 0;
            drone.lastExtinguishProgressAtMs = 0;
            drone.lastExtinguishAgent = drone.agent;
            drone.hasArrivedForCurrentMission = false;
            drone.warningLogged = false;

            List<FireEvent> zoneEvents = eventsPerZone.get(zoneId);
            if (zoneEvents != null && completed != null) {
                zoneEvents.remove(completed);

                if (zoneEvents.isEmpty()) {
                    eventsPerZone.remove(zoneId);
                    gui.setZoneOnFire(zoneId, false, null);
                } else {
                    gui.setZoneOnFire(zoneId, true, zoneEvents.get(0).severity);
                }
            }
        }

        printEventsPerZone();
        gui.updateDroneStatus(droneId, "IDLE");

        EventLogger.log("SCHEDULER", "DRONE_COMPLETE_RECEIVED",
                "drone=" + droneId + " zone=" + zoneId +
                        " newWorkload=" + (drone != null ? drone.workload : -1));

        checkForSystemShutdown();
    }

    private void send(String msg, InetAddress addr, int port) throws Exception {
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
        socket.send(packet);
        EventLogger.log("SCHEDULER", "MESSAGE_SENT",
                "to=" + addr + ":" + port + " msg=" + msg);
    }

    private void detectTimeoutFaults() {
        // fault handling: timeout-based stuck-flight and no-progress detection
        long now = System.currentTimeMillis();
        for (DroneInfo drone : drones.values()) {
            if (drone.offline || drone.assignedEvent == null) {
                continue;
            }

            if ("EN_ROUTE".equalsIgnoreCase(drone.state) || "ASSIGNED".equalsIgnoreCase(drone.state)) {
                long start = drone.assignedAtMs > 0 ? drone.assignedAtMs : now;
                long elapsed = now - start; // NEW
                
                // Soft Fault (i.e. warning)
                if (elapsed > EN_ROUTE_WARNING_MS && drone.lastArrivedAtMs < start) {
                    if (!drone.warningLogged) {
                        drone.warningLogged = true;
                        EventLogger.log("SCHEDULER", "SOFT_FAULT_DETECTED", 
                            "drone=" + drone.id + " elapsed=" + elapsed + "ms");
                        // No call to markDroneFaultAndRequeue()
                    }
                }

                // Hard Fault (FAULTED)
                if (elapsed > EN_ROUTE_TIMEOUT_MS && drone.lastArrivedAtMs < start) {
                    EventLogger.log("SCHEDULER", "HARD_FAULT_TIMEOUT_DETECTED",
                            "drone=" + drone.id + " elapsed=" + elapsed +
                                    "ms assignedZone=" + (drone.assignedEvent != null ? drone.assignedEvent.zoneId : -1));
                    markDroneFaultAndRequeue(drone, "HARD_FAULT:STUCK_IN_FLIGHT_FAULT");
                }
            }

            if ("EXTINGUISHING".equalsIgnoreCase(drone.state)) {
                if (now - drone.lastExtinguishProgressAtMs > EXTINGUISH_PROGRESS_TIMEOUT_MS) {
                    EventLogger.log("SCHEDULER", "HARD_FAULT_NO_PROGRESS_DETECTED",
                            "drone=" + drone.id + " zone=" +
                                    (drone.assignedEvent != null ? drone.assignedEvent.zoneId : -1) +
                                    " timeoutMs=" + EXTINGUISH_PROGRESS_TIMEOUT_MS);
                    markDroneFaultAndRequeue(drone, "HARD_FAULT:NOZZLE_OR_BAY_DOOR_STUCK");
                }
            }
        }


    }

    private String getHighestSeverity(int zoneId) {
        List<FireEvent> zoneEvents = eventsPerZone.get(zoneId);
        if (zoneEvents == null || zoneEvents.isEmpty()) return "0"; // grey

        // Example: assuming severity is "LOW", "MEDIUM", "HIGH"
        if (zoneEvents.stream().anyMatch(e -> "HIGH".equalsIgnoreCase(e.severity))) return "HIGH";
        if (zoneEvents.stream().anyMatch(e -> "MEDIUM".equalsIgnoreCase(e.severity))) return "MEDIUM";
        return "LOW";
    }

    private void markDroneFaultAndRequeue(DroneInfo drone, String reason) {

        EventLogger.log("SCHEDULER", "FAULT_HANDLING_STARTED",
                "drone=" + drone.id + " reason=" + reason +
                        " assignedZone=" + (drone.assignedEvent != null ? drone.assignedEvent.zoneId : -1));

        if (drone.offline) return;

        // mark the drone as faulted
        drone.offline = true;
        drone.state = "FAULTED";
        gui.updateDroneStatus(drone.id, "FAULTED");
        EventLogger.log("SCHEDULER", "DRONE_FAULT_DETECTED",
                "drone=" + drone.id + " reason=" + reason);

        if (drone.assignedEvent != null) {
            FireEvent faultedEvent = drone.assignedEvent;


            List<FireEvent> zoneEvents = eventsPerZone.get(faultedEvent.zoneId);
            if (zoneEvents != null) {
                zoneEvents.remove(faultedEvent);
                if (zoneEvents.isEmpty()) {
                    gui.setZoneOnFire(faultedEvent.zoneId, false, ""); // turn gray
                } else {
                    FireEvent next = zoneEvents.get(0);
                    gui.setZoneOnFire(faultedEvent.zoneId, true, next.severity);
                }
            }


            FireEvent requeuedEvent = new FireEvent(
                    faultedEvent.time,
                    faultedEvent.zoneId,
                    faultedEvent.type,
                    faultedEvent.severity,
                    faultedEvent.centerX,
                    faultedEvent.centerY,
                    FaultType.NONE
            );
            pendingEvents.add(requeuedEvent);

            EventLogger.log("SCHEDULER", "EVENT_REQUEUED_AFTER_FAULT",
                    "drone=" + drone.id + " zone=" + requeuedEvent.zoneId);

            drone.assignedEvent = null;


            attemptReassignmentWithRetry(requeuedEvent);


            printEventsPerZone();
        }
    }

    private void attemptReassignmentWithRetry(FireEvent event) {
        new Thread(() -> {
            while (true) {
                boolean assigned = false;

                synchronized (pendingEvents) {

                    // if already assigned by another thread → stop
                    if (!pendingEvents.contains(event)) {
                        return;
                    }

                    for (DroneInfo d : drones.values()) {
                        if (!d.offline && "IDLE".equalsIgnoreCase(d.state)) {
                            try {
                                send("ASSIGN," + event.time + "," + event.zoneId + "," +
                                                event.type + "," + event.severity + "," +
                                                event.centerX + "," + event.centerY + "," +
                                                event.faultType.name(),
                                        d.address, d.port);


                                d.state = "ASSIGNED";
                                d.assignedEvent = event;
                                d.assignedAtMs = System.currentTimeMillis();


                                pendingEvents.remove(event);


                                eventsPerZone.putIfAbsent(event.zoneId, new ArrayList<>());
                                eventsPerZone.get(event.zoneId).add(event);

                                gui.setZoneOnFire(event.zoneId, true, event.severity);
                                gui.updateZone(d.id, event.zoneId);

                                EventLogger.log("SCHEDULER", "REASSIGNED_AFTER_FAULT",
                                        "drone=" + d.id + " zone=" + event.zoneId);

                                printEventsPerZone();

                                assigned = true;
                                break;
                            } catch (Exception e) {
                                EventLogger.log("SCHEDULER", "REASSIGN_FAILED",
                                        "drone=" + d.id + " error=" + e.getMessage());
                            }
                        }
                    }
                }

                if (assigned) break;

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private boolean allWorkFinished() {
        if (!pendingEvents.isEmpty()) {
            return false;
        }

        for (Map.Entry<Integer, List<FireEvent>> entry : eventsPerZone.entrySet()) {
            List<FireEvent> list = entry.getValue();
            if (list != null && !list.isEmpty()) {
                return false;
            }
        }

        for (DroneInfo d : drones.values()) {
            if (!d.offline && d.assignedEvent != null) {
                return false;
            }
        }

        return true;
    }

    private boolean allActiveDronesAtBaseAndIdle() {
        for (DroneInfo d : drones.values()) {
            if (d.offline) {
                continue;
            }

            boolean atBase = (d.position != null && d.position.x == 0 && d.position.y == 0);
            boolean idleLike =
                    "IDLE".equalsIgnoreCase(d.state) ||
                            "REFILLED".equalsIgnoreCase(d.state);

            boolean noMission = (d.assignedEvent == null);

            if (!(atBase && idleLike && noMission)) {
                return false;
            }
        }
        return true;
    }

    private void sendReturnToFieldDrones() {
        for (DroneInfo d : drones.values()) {
            if (d.offline) continue;
            if (d.address == null) continue;

            boolean atBase = (d.position.x == 0 && d.position.y == 0);
            if (!atBase) {
                try {
                    send("RETURN", d.address, d.port);
                    EventLogger.log("SCHEDULER", "RETURN_SENT",
                            "drone=" + d.id + " x=" + d.position.x + " y=" + d.position.y);
                } catch (Exception e) {
                    EventLogger.log("SCHEDULER", "RETURN_SEND_FAILED",
                            "drone=" + d.id + " error=" + e.getMessage());
                }
            }
        }
    }

    private void sendShutdownToBaseDrones() {
        for (DroneInfo d : drones.values()) {
            if (d.offline) continue;
            if (d.address == null) continue;

            boolean atBase = (d.position.x == 0 && d.position.y == 0);
            if (atBase) {
                try {
                    send("SHUTDOWN", d.address, d.port);
                    EventLogger.log("SCHEDULER", "SHUTDOWN_SENT",
                            "drone=" + d.id);
                } catch (Exception e) {
                    EventLogger.log("SCHEDULER", "SHUTDOWN_SEND_FAILED",
                            "drone=" + d.id + " error=" + e.getMessage());
                }
            }
        }
    }

    private void terminateScheduler() {
        EventLogger.log("SCHEDULER", "EXIT",
                "message=All fires extinguished. All drones returned to base. System shutting down.");

        System.out.println("[Scheduler] EXIT: All fires extinguished. All drones returned to base. System shutting down.");

        running = false;

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void checkForSystemShutdown() {
        boolean workFinished = allWorkFinished();
        boolean allAtBaseIdle = allActiveDronesAtBaseAndIdle();

        EventLogger.log("SCHEDULER", "SHUTDOWN_CHECK",
                "noMoreEvents=" + noMoreEvents +
                        " workFinished=" + workFinished +
                        " allAtBaseIdle=" + allAtBaseIdle +
                        " pendingEvents=" + pendingEvents.size());

        for (DroneInfo d : drones.values()) {
            EventLogger.log("SCHEDULER", "SHUTDOWN_CHECK_DRONE",
                    "drone=" + d.id +
                            " offline=" + d.offline +
                            " state=" + d.state +
                            " x=" + d.position.x +
                            " y=" + d.position.y +
                            " assignedEvent=" + (d.assignedEvent != null ? d.assignedEvent.zoneId : "null"));
        }

        if (!noMoreEvents) return;
        if (!workFinished) return;

        sendReturnToFieldDrones();

        if (allAtBaseIdle && !shutdownInitiated) {
            shutdownInitiated = true;
            sendShutdownToBaseDrones();
            terminateScheduler();
        }
    }

    public void shutdownFromGui() {
        EventLogger.log("SCHEDULER", "GUI_CLOSE_REQUEST",
                "message=GUI window closed by user");

        System.out.println("[Scheduler] GUI closed. Shutting down scheduler.");

        running = false;

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        System.exit(0);
    }

    public static class DroneInfo {
        final int id;
        InetAddress address;
        int port;
        String state = "IDLE";
        Point position = new Point(0, 0);
        int agent = 14;
        int workload = 0;
        // fault handling: runtime tracking fields
        boolean offline = false;
        boolean hasArrivedForCurrentMission = false;
        boolean warningLogged = false;
        FireEvent assignedEvent;
        long assignedAtMs = 0;
        long lastStatusAtMs = 0;
        long lastPositionAtMs = 0;
        long lastArrivedAtMs = 0;
        int lastExtinguishAgent = 14;
        long lastExtinguishProgressAtMs = 0;

        DroneInfo(int id) {
            this.id = id;
        }
    }

    public static void main(String[] args) throws Exception {
        EventLogger.clearLog();
        new SchedulerUDP(7000).start();
    }
}
