package antikristianos.foraging;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="zombie.iso.IsoWorld",methodName="KillCell",warmUp=true,strictMatch=true)
public final class PatchLocalQuit {
    @Patch.OnEnter public static void enter() {RuntimeBridge.shutdown();}
}
