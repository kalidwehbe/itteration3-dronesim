import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerformanceMetricsGenerator {
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern KV_PATTERN =
            Pattern.compile("(\\w+)=([^\\s]+)");

    private static class EventMetric {
        final String key;
        final int zoneId;
        final String eventTime;
        final String severity;

        LocalDateTime receivedAt;
        LocalDateTime firstAssignedAt;
        LocalDateTime firstArrivedAt;
        LocalDateTime completedAt;

        Integer assignedDroneId;
        Integer completedByDroneId;

        EventMetric(String key, int zoneId, String eventTime,
                    String severity, LocalDateTime receivedAt) {
            this.key = key;
            this.zoneId = zoneId;
            this.eventTime = eventTime;
            this.severity = severity;
            this.receivedAt = receivedAt;
        }

        long queueWaitMs() {
            if (receivedAt == null || firstAssignedAt == null) return -1;
            return Math.max(0, ChronoUnit.MILLIS.between(receivedAt, firstAssignedAt));
        }

        long responseMs() {
            if (receivedAt == null || firstArrivedAt == null) return -1;
            return Math.max(0, ChronoUnit.MILLIS.between(receivedAt, firstArrivedAt));
        }

        long completionMs() {
            if (receivedAt == null || completedAt == null) return -1;
            return Math.max(0, ChronoUnit.MILLIS.between(receivedAt, completedAt));
        }

        long serviceMs() {
            if (firstArrivedAt == null || completedAt == null) return -1;
            return Math.max(0, ChronoUnit.MILLIS.between(firstArrivedAt, completedAt));
        }
    }

    private static class DroneMetric {
        final int droneId;

        LocalDateTime firstSeen;
        LocalDateTime endTime;

        String currentState;
        LocalDateTime currentStateStart;

        double distanceMeters = 0.0;
        Integer lastX;
        Integer lastY;

        long travelMs = 0;
        long serviceMs = 0;

        int missionsAssigned = 0;
        int firesHandled = 0;

        DroneMetric(int droneId) {
            this.droneId = droneId;
        }

        void seenAt(LocalDateTime ts) {
            if (firstSeen == null || ts.isBefore(firstSeen)) {
                firstSeen = ts;
            }
            if (endTime == null || ts.isAfter(endTime)) {
                endTime = ts;
            }
        }

        void transitionTo(String newState, LocalDateTime ts) {
            seenAt(ts);
            closeStateInterval(ts);
            currentState = newState;
            currentStateStart = ts;
        }

        void closeStateInterval(LocalDateTime ts) {
            if (currentState == null || currentStateStart == null || ts.isBefore(currentStateStart)) {
                return;
            }

            long delta = ChronoUnit.MILLIS.between(currentStateStart, ts);
            if (delta < 0) delta = 0;

            if (isTravelState(currentState)) {
                travelMs += delta;
            }
            if (isServiceState(currentState)) {
                serviceMs += delta;
            }
        }

        long totalLifetimeMs() {
            if (firstSeen == null || endTime == null || endTime.isBefore(firstSeen)) {
                return 0;
            }
            return ChronoUnit.MILLIS.between(firstSeen, endTime);
        }

        long activeWorkMs() {
            return travelMs + serviceMs;
        }

        long idleMs() {
            return Math.max(0, totalLifetimeMs() - activeWorkMs());
        }

        double utilizationPercent() {
            long total = totalLifetimeMs();
            if (total <= 0) return 0.0;
            return (100.0 * activeWorkMs()) / total;
        }
    }

    public static void generateFromEventLog(String inputPath, String outputPath) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(inputPath));

        Map<String, EventMetric> events = new LinkedHashMap<>();
        Map<Integer, DroneMetric> drones = new TreeMap<>();
        Map<Integer, Deque<String>> outstandingEventsByZone = new HashMap<>();

        LocalDateTime firstEventReceivedAt = null;
        LocalDateTime lastFireCompletedAt = null;

        for (String line : lines) {
            String[] parts = line.split(" \\| ", 4);
            if (parts.length < 4) continue;

            LocalDateTime ts;
            try {
                ts = LocalDateTime.parse(parts[0].trim(), TS_FMT);
            } catch (Exception e) {
                continue;
            }

            String subsystem = parts[1].trim();
            String event = parts[2].trim();
            String details = parts[3].trim();

            Map<String, String> kv = parseDetails(details);

            if ("SCHEDULER".equals(subsystem) && "EVENT_RECEIVED".equals(event)) {
                int zoneId = parseInt(kv.get("zone"), -1);
                String eventTime = kv.getOrDefault("time", "UNKNOWN");
                String severity = kv.getOrDefault("severity", "UNKNOWN");

                String key = buildEventKey(zoneId, eventTime);
                EventMetric metric = new EventMetric(key, zoneId, eventTime, severity, ts);
                events.put(key, metric);

                outstandingEventsByZone
                        .computeIfAbsent(zoneId, z -> new ArrayDeque<>())
                        .addLast(key);

                if (firstEventReceivedAt == null || ts.isBefore(firstEventReceivedAt)) {
                    firstEventReceivedAt = ts;
                }
                continue;
            }

            if ("SCHEDULER".equals(subsystem) && "DRONE_ASSIGNED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                int zoneId = parseInt(kv.get("zone"), -1);
                String eventTime = kv.getOrDefault("eventTime", "UNKNOWN");

                String key = buildEventKey(zoneId, eventTime);
                EventMetric metric = events.get(key);
                if (metric != null && metric.firstAssignedAt == null) {
                    metric.firstAssignedAt = ts;
                    metric.assignedDroneId = droneId;
                }

                if (droneId >= 0) {
                    drone(drones, droneId).missionsAssigned++;
                }
                continue;
            }

            if ("SCHEDULER".equals(subsystem) && "DRONE_ARRIVED_RECEIVED".equals(event)) {
                int zoneId = parseInt(kv.get("zone"), -1);
                Deque<String> queue = outstandingEventsByZone.get(zoneId);
                if (queue != null && !queue.isEmpty()) {
                    EventMetric metric = events.get(queue.peekFirst());
                    if (metric != null && metric.firstArrivedAt == null) {
                        metric.firstArrivedAt = ts;
                    }
                }
                continue;
            }

            if ("SCHEDULER".equals(subsystem) && "DRONE_COMPLETE_RECEIVED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                int zoneId = parseInt(kv.get("zone"), -1);

                Deque<String> queue = outstandingEventsByZone.get(zoneId);
                if (queue != null && !queue.isEmpty()) {
                    String key = queue.removeFirst();
                    EventMetric metric = events.get(key);
                    if (metric != null) {
                        metric.completedAt = ts;
                        metric.completedByDroneId = droneId;
                    }
                }

                if (droneId >= 0) {
                    drone(drones, droneId).firesHandled++;
                }

                if (lastFireCompletedAt == null || ts.isAfter(lastFireCompletedAt)) {
                    lastFireCompletedAt = ts;
                }
                continue;
            }

            if ("SCHEDULER".equals(subsystem) && "DRONE_STATUS_RECEIVED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                String state = kv.getOrDefault("state", "UNKNOWN");
                if (droneId >= 0) {
                    drone(drones, droneId).transitionTo(state, ts);
                }
                continue;
            }

            if ("SCHEDULER".equals(subsystem) && "DRONE_POSITION_RECEIVED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                int x = parseInt(kv.get("x"), 0);
                int y = parseInt(kv.get("y"), 0);

                if (droneId >= 0) {
                    DroneMetric dm = drone(drones, droneId);
                    dm.seenAt(ts);

                    if (dm.lastX != null && dm.lastY != null) {
                        dm.distanceMeters += Math.hypot(x - dm.lastX, y - dm.lastY);
                    }

                    dm.lastX = x;
                    dm.lastY = y;
                }
                continue;
            }

            if ("DRONE".equals(subsystem) && "STARTED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                if (droneId >= 0) {
                    drone(drones, droneId).seenAt(ts);
                }
                continue;
            }

            if ("DRONE".equals(subsystem) && "PROCESS_TERMINATED".equals(event)) {
                int droneId = parseInt(kv.get("drone"), -1);
                if (droneId >= 0) {
                    DroneMetric dm = drone(drones, droneId);
                    dm.seenAt(ts);
                    dm.closeStateInterval(ts);
                    dm.endTime = ts;
                }
            }
        }

        for (DroneMetric dm : drones.values()) {
            if (dm.endTime != null) {
                dm.closeStateInterval(dm.endTime);
            }
        }

        writeReport(outputPath, events, drones, firstEventReceivedAt, lastFireCompletedAt);
    }

    private static void writeReport(String outputPath,
                                    Map<String, EventMetric> events,
                                    Map<Integer, DroneMetric> drones,
                                    LocalDateTime firstEventReceivedAt,
                                    LocalDateTime lastFireCompletedAt) throws IOException {

        int completedCount = 0;

        long totalQueueWaitMs = 0;
        long totalResponseMs = 0;
        long totalCompletionMs = 0;

        long maxResponseMs = -1;
        long maxCompletionMs = -1;

        int queueSamples = 0;
        int responseSamples = 0;
        int completionSamples = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("PERFORMANCE METRICS\n");
        sb.append("===================\n\n");

        sb.append("Event Metrics\n");
        sb.append("-------------\n");

        for (EventMetric metric : events.values()) {
            sb.append("Zone ").append(metric.zoneId)
                    .append(" (eventTime=").append(metric.eventTime)
                    .append(", severity=").append(metric.severity).append(")\n");

            long queueWait = metric.queueWaitMs();
            if (queueWait >= 0) {
                totalQueueWaitMs += queueWait;
                queueSamples++;
                sb.append("  Queue Wait Time: ").append(formatDuration(queueWait)).append("\n");
            } else {
                sb.append("  Queue Wait Time: N/A\n");
            }

            long responseTime = metric.responseMs();
            if (responseTime >= 0) {
                totalResponseMs += responseTime;
                responseSamples++;
                maxResponseMs = Math.max(maxResponseMs, responseTime);
                sb.append("  Event Response Time: ").append(formatDuration(responseTime)).append("\n");
            } else {
                sb.append("  Event Response Time: N/A\n");
            }

            long completionTime = metric.completionMs();
            if (completionTime >= 0) {
                totalCompletionMs += completionTime;
                completionSamples++;
                completedCount++;
                maxCompletionMs = Math.max(maxCompletionMs, completionTime);
                sb.append("  Event Completion Time: ").append(formatDuration(completionTime)).append("\n");
                sb.append("  Completed by drone: ").append(metric.completedByDroneId).append("\n");
            } else {
                sb.append("  Event Completion Time: N/A\n");
            }

            long serviceTime = metric.serviceMs();
            if (serviceTime >= 0) {
                sb.append("  Service Time After Arrival: ").append(formatDuration(serviceTime)).append("\n");
            } else {
                sb.append("  Service Time After Arrival: N/A\n");
            }

            sb.append("\n");
        }

        sb.append("Summary Metrics\n");
        sb.append("---------------\n");
        sb.append("Number of fires handled: ").append(completedCount).append("\n");

        sb.append("Average Queue Wait Time: ")
                .append(queueSamples == 0 ? "N/A" : formatDuration(totalQueueWaitMs / queueSamples))
                .append("\n");

        sb.append("Average Event Response Time: ")
                .append(responseSamples == 0 ? "N/A" : formatDuration(totalResponseMs / responseSamples))
                .append("\n");

        sb.append("Maximum Event Response Time: ")
                .append(maxResponseMs < 0 ? "N/A" : formatDuration(maxResponseMs))
                .append("\n");

        sb.append("Average Event Completion Time: ")
                .append(completionSamples == 0 ? "N/A" : formatDuration(totalCompletionMs / completionSamples))
                .append("\n");

        sb.append("Maximum Event Completion Time: ")
                .append(maxCompletionMs < 0 ? "N/A" : formatDuration(maxCompletionMs))
                .append("\n");

        if (firstEventReceivedAt != null && lastFireCompletedAt != null) {
            sb.append("Total Time to Extinguish All Fires: ")
                    .append(formatDuration(millisBetween(firstEventReceivedAt, lastFireCompletedAt)))
                    .append("\n");
        } else {
            sb.append("Total Time to Extinguish All Fires: N/A\n");
        }

        sb.append("\n");
        sb.append("Per-Drone Metrics\n");
        sb.append("-----------------\n");

        for (DroneMetric dm : drones.values()) {
            sb.append("Drone ").append(dm.droneId).append("\n");
            sb.append("  Missions Assigned: ").append(dm.missionsAssigned).append("\n");
            sb.append("  Fires Handled: ").append(dm.firesHandled).append("\n");
            sb.append("  Travel Time: ").append(formatDuration(dm.travelMs)).append("\n");
            sb.append("  Service Time: ").append(formatDuration(dm.serviceMs)).append("\n");
            sb.append("  Active Work Time: ").append(formatDuration(dm.activeWorkMs())).append("\n");
            sb.append("  Idle Time: ").append(formatDuration(dm.idleMs())).append("\n");
            sb.append("  Distance Traveled: ").append(String.format(Locale.US, "%.2f m", dm.distanceMeters)).append("\n");
            sb.append("  Drone Utilization: ").append(String.format(Locale.US, "%.2f%%", dm.utilizationPercent())).append("\n");
            sb.append("\n");
        }

        Files.writeString(Paths.get(outputPath), sb.toString());
    }

    private static DroneMetric drone(Map<Integer, DroneMetric> drones, int droneId) {
        return drones.computeIfAbsent(droneId, DroneMetric::new);
    }

    private static boolean isTravelState(String state) {
        return "TAKEOFF".equalsIgnoreCase(state)
                || "EN_ROUTE".equalsIgnoreCase(state)
                || "RETURNING".equalsIgnoreCase(state);
    }

    private static boolean isServiceState(String state) {
        return "EXTINGUISHING".equalsIgnoreCase(state)
                || "SERVICING".equalsIgnoreCase(state)
                || "SOFT_FAULTED".equalsIgnoreCase(state);
    }

    private static long millisBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return 0;
        return Math.max(0, ChronoUnit.MILLIS.between(start, end));
    }

    private static String buildEventKey(int zoneId, String eventTime) {
        return zoneId + "|" + eventTime;
    }

    private static Map<String, String> parseDetails(String details) {
        Map<String, String> map = new HashMap<>();
        Matcher matcher = KV_PATTERN.matcher(details);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        return map;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long ms = millis % 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, ms);
    }

    public static void clearMetricsFile(String outputPath) {
        try {
            Files.writeString(Paths.get(outputPath), "");
        } catch (IOException e) {
            System.out.println("Failed to clear performance metrics file: " + e.getMessage());
        }
    }
}