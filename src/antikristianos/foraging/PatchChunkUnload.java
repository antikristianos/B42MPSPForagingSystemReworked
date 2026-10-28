package antikristianos.foraging;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoChunk;

@Patch(className="zombie.iso.IsoChunk",methodName="removeFromWorld",warmUp=true,strictMatch=true)
public final class PatchChunkUnload {
    @Patch.OnEnter public static void enter(@Patch.This IsoChunk chunk) {RuntimeBridge.chunkUnloading(chunk);}
}
