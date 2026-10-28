package antikristianos.foraging;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="zombie.network.ServerMap",methodName="QueuedSaveAll",warmUp=true,strictMatch=true)
public final class PatchServerQuit {
    @Patch.OnEnter public static void enter(@Patch.Argument(0) boolean quit) {if(quit) RuntimeBridge.shutdown();}
}
