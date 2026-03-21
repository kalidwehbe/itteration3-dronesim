import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FireGUI extends JFrame {

    // --- Drone info maps ---
    private Map<Integer, JLabel> droneStatusMap = new ConcurrentHashMap<>();
    private Map<Integer, JLabel> droneZoneMap = new ConcurrentHashMap<>();
    private Map<Integer, JLabel> droneAgentMap = new ConcurrentHashMap<>();

    // --- Panels ---
    private JPanel droneInfoPanel;
    private ZonePanel zonePanel;
    private JTextArea logArea;

    public FireGUI() {
        setTitle("Firefighting Drone System");
        setSize(1000, 600);
        setLayout(new BorderLayout());

        // --- Drone info panel (top) ---
        droneInfoPanel = new JPanel();
        droneInfoPanel.setLayout(new GridLayout(1, 3, 10, 10));
        droneInfoPanel.setBorder(BorderFactory.createTitledBorder("Drones"));
        add(droneInfoPanel, BorderLayout.NORTH);

        // --- Zone panel (center) ---
        zonePanel = new ZonePanel();
        add(zonePanel, BorderLayout.CENTER);

        // --- Log panel (bottom, optional) ---
        logArea = new JTextArea(8, 30);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("System Log"));
        add(scrollPane, BorderLayout.SOUTH);

        setVisible(true);
    }

    // --- Register drone in the info panel ---
    public void registerDrone(int id) {
        JPanel panel = new JPanel(new GridLayout(4, 1));
        panel.setBorder(BorderFactory.createTitledBorder("Drone " + id));

        JLabel idLabel = new JLabel("ID: " + id);
        JLabel statusLabel = new JLabel("State: IDLE");
        JLabel zoneLabel = new JLabel("Zone: -");
        JLabel agentLabel = new JLabel("Agent: 14L");

        droneStatusMap.put(id, statusLabel);
        droneZoneMap.put(id, zoneLabel);
        droneAgentMap.put(id, agentLabel);

        panel.add(idLabel);
        panel.add(statusLabel);
        panel.add(zoneLabel);
        panel.add(agentLabel);

        droneInfoPanel.add(panel);
        revalidate();
        repaint();
    }

    // --- Update drone info ---
    public void updateDroneStatus(int id, String status) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = droneStatusMap.get(id);
            if (label != null) label.setText("State: " + status);
        });
    }

    public void updateZone(int id, int zoneId) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = droneZoneMap.get(id);
            if (label != null) label.setText("Zone: " + zoneId);
        });
    }

    public void updateAgent(int id, int agent) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = droneAgentMap.get(id);
            if (label != null) label.setText("Agent: " + agent + "L");
        });
    }

    public void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    public void updateDronePosition(int droneId, int x, int y) {
        zonePanel.updateDronePosition(droneId, x, y);
    }

    public void setZones(Map<Integer, FireZone> zones) {
        zonePanel.setZones(zones);
    }

    public void setZoneOnFire(int zoneId, boolean onFire, String severity) {
        zonePanel.setZoneOnFire(zoneId, onFire, severity);
    }


    // ================== Inner Zone Panel ==================
    static class ZonePanel extends JPanel {
        private final Map<Integer, FireZone> zones = new HashMap<>();
        private final Map<Integer, Point> dronePositions = new HashMap<>();
        private final Map<Integer, Integer> activeFires = new HashMap<>(); // count of active fires per zone
        private final Map<Integer, String> fireSeverity = new HashMap<>();

        public void setZones(Map<Integer, FireZone> newZones) {
            synchronized (zones) {
                zones.clear();
                zones.putAll(newZones);
            }
            SwingUtilities.invokeLater(this::repaint);
        }

        public void updateDronePosition(int droneId, int x, int y) {
            synchronized (dronePositions) {
                dronePositions.put(droneId, new Point(x, y));
            }
            SwingUtilities.invokeLater(this::repaint);
        }

        // Increment or decrement fire count for a zone
        public void setZoneOnFire(int zoneId, boolean onFire, String severity) {
            synchronized (activeFires) {
                int count = activeFires.getOrDefault(zoneId, 0);

                if (onFire) {
                    count++; //New fire event
                    activeFires.put(zoneId, count);
                    fireSeverity.put(zoneId, severity);
                } else {
                    count--;
                    if (count <= 0) { //No more fire
                        activeFires.remove(zoneId);
                        fireSeverity.remove(zoneId);
                    } else {
                        activeFires.put(zoneId, count);
                    }
                }
            }
            SwingUtilities.invokeLater(this::repaint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());

            double zoom = 0.2;
            Map<Integer, FireZone> zonesCopy;
            synchronized (zones) {
                zonesCopy = new HashMap<>(zones);
            }

            for (FireZone z : zonesCopy.values()) {
                int x = (int) (z.x1 * zoom);
                int y = (int) (z.y1 * zoom);
                int w = Math.max((int) ((z.x2 - z.x1) * zoom), 1);
                int h = Math.max((int) ((z.y2 - z.y1) * zoom), 1);

                boolean isOnFire;
                String severity = null;

                synchronized (activeFires) {
                    isOnFire = activeFires.containsKey(z.id);
                    if (isOnFire) {
                        severity = fireSeverity.get(z.id);
                    }
                }
                if (isOnFire) {
                    if (severity != null && severity.equalsIgnoreCase("Low")) {
                        g.setColor(Color.YELLOW);
                    } else if (severity != null && severity.equalsIgnoreCase("Moderate")) {
                        g.setColor(Color.ORANGE);
                    } else if (severity != null && severity.equalsIgnoreCase("High")) {
                        g.setColor(Color.RED);
                    } else {
                        g.setColor(Color.ORANGE); // fallback if severity text is unexpected
                    }
                } else {
                    g.setColor(Color.LIGHT_GRAY);
                }
                g.fillRect(x, y, w, h);

                g.setColor(Color.BLACK);
                g.drawRect(x, y, w, h);
                g.drawString("Zone " + z.id, x + 2, y + 12);

                if (isOnFire) {
                    g.setColor(Color.RED);
                    g.drawString("FIRE", x + 2, y + 25);
                }
            }

            synchronized (dronePositions) {
                g.setColor(Color.BLUE);
                for (Point p : dronePositions.values()) {
                    int dx = (int) (p.x * zoom);
                    int dy = (int) (p.y * zoom);
                    g.fillOval(dx - 5, dy - 5, 10, 10);
                }
            }
        }
    }
}
