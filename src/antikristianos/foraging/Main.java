package antikristianos.foraging;

public final class Main {
    public static final String VERSION="0.2";
    private Main() {}
    public static void main(String[] args) {
        me.zed_0xff.zombie_buddy.Exposer.exposeClass(RuntimeBridge.class);
        System.out.println("[B42Foraging] Java "+VERSION+" loaded; native-zone stock candidate; regular Vanilla findings retained until zone adapter acceptance");
    }
}
