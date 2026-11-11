package antikristianos.foraging;

public final class Main {
    public static final String VERSION="0.2.4";
    private Main() {}
    public static void main(String[] args) {
        me.zed_0xff.zombie_buddy.Exposer.exposeClass(RuntimeBridge.class);
        System.out.println("[B42Foraging] Java "+VERSION+" loaded; authoritative shared finds, reservations, finder XP and native exclusive pickup; Vanilla bonus/track/world-item paths retained");
    }
}
