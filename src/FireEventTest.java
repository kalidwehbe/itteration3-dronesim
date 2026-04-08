import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FireEventTest {

    private FireEvent event1;
    private FireEvent event2;
    private FireEvent event3;

    @Before
    public void setUp() {
        // HH:MM:SS format
        event1 = new FireEvent("01:02:03", 1, "Fire", "High", 100, 200);

        // MM:SS format
        event2 = new FireEvent("10:30", 2, "Smoke", "Medium", 50, 75);

        // SS format
        event3 = new FireEvent("45", 3, "Heat", "Low", 10, 20);
    }

    @After
    public void tearDown() {
        event1 = null;
        event2 = null;
        event3 = null;
    }

    @Test
    public void testGetIntTime_HHMMSS() {
        // 1 hour = 3600, 2 min = 120, 3 sec = 3 → total = 3723
        assertEquals(3723, event1.getIntTime());
    }

    @Test
    public void testGetIntTime_MMSS() {
        // 10 min = 600, 30 sec = 30 → total = 630
        assertEquals(630, event2.getIntTime());
    }

    @Test
    public void testGetIntTime_SS() {
        // just seconds
        assertEquals(45, event3.getIntTime());
    }

    @Test
    public void testToString() {
        String expected = "01:02:03 Zone 1 Fire High";
        assertEquals(expected, event1.toString());
    }

    @Test
    public void testFieldsAssignedCorrectly() {
        assertEquals("01:02:03", event1.time);
        assertEquals(1, event1.zoneId);
        assertEquals("Fire", event1.type);
        assertEquals("High", event1.severity);
        assertEquals(100, event1.centerX);
        assertEquals(200, event1.centerY);
    }
}