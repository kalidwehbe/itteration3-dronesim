import java.io.*;
import java.net.*;
import java.util.*;

public class FireIncidentSubsystem {

    private DatagramSocket socket;
    private InetAddress schedulerAddress;
    private int schedulerPort;
    private Map<Integer, FireZone> zones = new HashMap<>();

    public FireIncidentSubsystem(String schedulerHost, int schedulerPort) throws Exception {
        this.socket = new DatagramSocket();
        this.schedulerAddress = InetAddress.getByName(schedulerHost);
        this.schedulerPort = schedulerPort;
    }

    // --- Read zones from CSV and send to Scheduler ---
    public void readZones(String filename) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;

            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.trim().isEmpty()) continue;

                String[] p = line.split(",");
                int zoneId = Integer.parseInt(p[0].trim());

                String[] start = p[1].replace("(", "").replace(")", "").split(";");
                String[] end = p[2].replace("(", "").replace(")", "").split(";");

                int x1 = Integer.parseInt(start[0].trim());
                int y1 = Integer.parseInt(start[1].trim());
                int x2 = Integer.parseInt(end[0].trim());
                int y2 = Integer.parseInt(end[1].trim());

                FireZone zone = new FireZone(zoneId, x1, y1, x2, y2);
                zones.put(zoneId, zone);

                String msg = "ZONE," + zoneId + "," + x1 + "," + y1 + "," + x2 + "," + y2;
                sendMessage(msg);

                System.out.println("[FireIncident] Sent zone " + zoneId);
            }
        }
    }

    public static final double TIME_FACTOR = 0.1;

    // --- Read events from CSV and send to Scheduler ---
    public void readEvents(String filename) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;
            int prevEventTime = -1;

            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.trim().isEmpty()) continue;

                String[] p = line.split(",");
                String time = p[0].trim();
                int zoneId = Integer.parseInt(p[1].trim());
                String type = p[2].trim();
                String severity = p[3].trim();

                FaultType faultType = (p.length >= 5) ? FaultType.fromString(p[4]) : FaultType.NONE; // Parse the fault type from the csv

                FireZone zone = zones.get(zoneId);
                if (zone == null) {
                    System.out.println("[FireIncident] ERROR: Zone not found for zoneId=" + zoneId);
                    continue;
                }

                int centerX = zone.centerX();
                int centerY = zone.centerY();

                int currentTime = new FireEvent(time, zoneId, type, severity, centerX, centerY, faultType).getIntTime();
                if (prevEventTime != -1) {
                    int diffSeconds = currentTime - prevEventTime;
                    if (diffSeconds > 0) {
                        Thread.sleep((long) (diffSeconds * 1000 * TIME_FACTOR));
                    }
                }
                prevEventTime = currentTime;

                String msg = "EVENT," + time + "," + zoneId + "," + type + "," + severity + "," + centerX + "," + centerY + "," + faultType.name();
                sendMessage(msg);
                System.out.println("[FireIncident] Sent event for zone " + zoneId);
            }
        }
    }

    private void sendMessage(String msg) throws Exception {
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, schedulerAddress, schedulerPort);
        socket.send(packet);
    }

    public static void main(String[] args) throws Exception {
        FireIncidentSubsystem fire = new FireIncidentSubsystem("localhost", 7000);
        fire.readZones("sample_zone_file.csv");
        fire.readEvents("sample_event_file.csv");
    }
}
