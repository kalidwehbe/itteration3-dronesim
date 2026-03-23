public class FireEvent {
    public final String time;
    public final int zoneId;
    public final String type;
    public final String severity;
    public final int centerX;
    public final int centerY;
    public final FaultType faultType;

    public FireEvent(String time, int zoneId, String type, String severity, int centerX, int centerY, FaultType faultType) {
        this.time = time;
        this.zoneId = zoneId;
        this.type = type;
        this.severity = severity;
        this.centerX = centerX;
        this.centerY = centerY;
        this.faultType = faultType;
    }

    @Override
    public String toString() {
        return time + " Zone " + zoneId + " " + type + " " + severity + ", FAULT=" + faultType;
    }

    public int getIntTime() {
        int numOfDelimiters = 0;
        for (int i = 0; i < this.time.length(); i++) {
            if (this.time.charAt(i) == ':') {
                numOfDelimiters++;
            }
        }

        String[] timeParts = this.time.split(":");
        int intTime = 0;
        int placeValue = numOfDelimiters;
        for (int i = 0; i <= numOfDelimiters; i++) {
            intTime += Integer.parseInt(timeParts[i]) * Math.pow(60, placeValue);
            placeValue--;
        }

        return intTime;
    }
}
