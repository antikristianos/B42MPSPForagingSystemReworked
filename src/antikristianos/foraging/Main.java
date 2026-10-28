package antikristianos.foraging;

public final class Main {
    public static final String VERSION="0.1.1";
    private Main() {}
    public static void main(String[] args) {
        me.zed_0xff.zombie_buddy.Exposer.exposeClass(RuntimeBridge.class);
        System.out.println("[B42Foraging] Java "+VERSION+" loaded; world-integration gate; regular Vanilla findings retained until world adapter acceptance");
    }
}
