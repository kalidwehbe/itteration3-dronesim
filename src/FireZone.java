public class FireZone {
    public final int id;
    public final int x1, y1, x2, y2;

    public FireZone(int id, int x1, int y1, int x2, int y2) {
        this.id = id;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }


    public int centerX() {
        return (x1 + x2) / 2;
    }

    public int centerY() {
        return (y1 + y2) / 2;
    }
}
