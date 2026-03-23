import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class EventLogger {
    private static final String LOG_FILE = "event_log.txt";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static synchronized void log(String subsystem, String event, String details) {
        String timestamp = LocalDateTime.now().format(FMT);
        String line = timestamp + " | " + subsystem + " | " + event + " | " + details;

        System.out.println(line);

        try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            out.println(line);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void clearLog() {
        try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE))) {
            out.print("");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
