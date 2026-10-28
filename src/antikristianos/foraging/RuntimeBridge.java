package antikristianos.foraging;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.iso.zones.Zone;
import zombie.network.GameClient;

/** First integration gate: real world identity, native lookup/unload and a light measurement roundtrip.
 * Regular Vanilla findings are intentionally not replaced before this gate is validated. */
@Exposer.LuaClass(name="B42ForagingBridge")
public final class RuntimeBridge {
    private RuntimeBridge() {}
    private static JsonStockStore store;
    private static StockService service;
    private static Path currentSave;
    private static WorldTime lastTime;
    private static LightQueries lights=new LightQueries();
    private static final Set<StockService.Chunk> observedChunks=new HashSet<>();
    private static final Set<Integer> measured=new HashSet<>();
    private static final Set<String> observed=new HashSet<>();
    private static boolean failureLogged;
    public static long chunkHookCalls,shutdownHookCalls;
    public static boolean beginWorld() {
        if(GameClient.client) return false;
        if(IsoWorld.instance==null||IsoWorld.instance.currentCell==null) return false;
        if(zombie.core.Core.getInstance().isNoSave()) return false;
        try {
            Path save=Path.of(ZomboidFileSystem.instance.getCurrentSaveDir()).toAbsolutePath().normalize();
            if(store!=null&&save.equals(currentSave)) return true;
            if(store!=null) {close(lastTime);if(store!=null) return false;}
            String gameVersion=zombie.core.Core.getInstance().getVersionNumber();
            if(!gameVersion.equals("42.22")&&!gameVersion.startsWith("42.22.")) throw new IllegalStateException("unsupported game build "+gameVersion);
            WorldTime now=clock();
            store=new JsonStockStore(save,now);service=new StockService(store);currentSave=save;lastTime=now;
            lights=new LightQueries();observedChunks.clear();observed.clear();measured.clear();failureLogged=false;
            log("WORLD_READY version="+Main.VERSION+" worldId="+store.worldId+" anchor="+store.anchor.hours()+" namespace="+JsonStockStore.NAMESPACE);
            return true;
        } catch(Exception failure) {error("world initialization",failure);return false;}
    }
    public static void observePlayer(IsoPlayer player) {
        if(player==null||!beginWorld()) return;
        try {
            int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
            observedChunks.add(StockService.Chunk.at(x,y));
            var zones=IsoWorld.instance.getMetaGrid().getZonesAt(x,y,0);
            KahluaTable forage=(KahluaTable)LuaManager.env.rawget("forageSystem");
            KahluaTable definitions=forage==null?null:(KahluaTable)forage.rawget("zoneDefs");
            int rank=0;
            for(int i=zones.size()-1;i>=0;i--) {
                Zone zone=zones.get(i);
                if(definitions==null||definitions.rawget(zone.getType())==null||!zone.contains(x,y,0)) continue;
                String key=zone.getType()+":"+zone.getX()+":"+zone.getY()+":"+zone.getWidth()+":"+zone.getHeight()+":"+zone.geometryType+":"+zone.points;
                if(observed.add(key)) log("ZONE_OBSERVED rank="+rank+" biome="+zone.getType()+" origin="+zone.getX()+","+zone.getY()+" geometry="+zone.geometryType+" nativeArea="+zone.getTotalArea()+" (capacity area not yet assigned)");
                rank++;
            }
            lastTime=clock();
        } catch(Exception failure) {error("native zone observation",failure);}
    }
    public static KahluaTable requestLight(IsoPlayer player) {
        if(player==null||!beginWorld()) return null;
        int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
        LightQueries.Request request=lights.request(player.getOnlineID(),x,y,z,System.nanoTime()/1_000_000);
        if(request==null) return null;
        KahluaTable data=LuaManager.platform.newTable();
        data.rawset("requestId",request.id());data.rawset("session",request.session());data.rawset("onlineId",(double)request.player());
        data.rawset("x",(double)x);data.rawset("y",(double)y);data.rawset("z",(double)z);
        return data;
    }
    public static boolean acceptLight(IsoPlayer player,KahluaTable data) {
        if(GameClient.client||store==null||player==null||data==null) return false;
        Object value=data.rawget("light"),id=data.rawget("requestId"),session=data.rawget("session");
        if(!(value instanceof Number number)||!(id instanceof String)||!(session instanceof String)) return false;
        int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
        if(!lights.accept(player.getOnlineID(),(String)id,(String)session,x,y,z,number.doubleValue(),System.nanoTime()/1_000_000)) return false;
        if(measured.add((int)player.getOnlineID())) log("LIGHT_ACCEPTED onlineId="+player.getOnlineID()+" value="+number.doubleValue()+" at="+x+","+y+","+z+" (measurement only)");
        return true;
    }
    public static void observeLocalLight(IsoPlayer player) {
        if(GameClient.client||zombie.network.GameServer.server||player==null||!beginWorld()) return;
        var square=player.getCurrentSquare();if(square==null) return;
        int index=player.getPlayerNum();if(index<0||index>=4) return;
        float value=square.getLightLevel(index);
        if(Float.isFinite(value)&&value>=0&&value<=1&&measured.add(index)) log("LIGHT_LOCAL player="+index+" value="+value+" (measurement only)");
    }
    public static void cancelSearch(IsoPlayer player) {if(!GameClient.client&&player!=null) lights.cancel(player.getOnlineID());}
    public static void chunkUnloading(IsoChunk chunk) {
        chunkHookCalls++;
        if(GameClient.client||store==null||chunk==null) return;
        try {
            StockService.Chunk key=new StockService.Chunk(chunk.wx,chunk.wy);
            service.unload(key,clock());
            if(observedChunks.remove(key)) log("CHUNK_UNLOAD "+key.x()+","+key.y()+" (authoritative native callback)");
        } catch(Exception failure) {error("chunk unload",failure);}
    }
    public static void shutdown() {
        shutdownHookCalls++;
        if(GameClient.client||store==null) return;
        close(IsoWorld.instance!=null&&IsoWorld.instance.currentCell!=null?clock():lastTime);
    }
    private static void close(WorldTime now) {
        try {
            service.shutdown(now);
            log("WORLD_CLOSED worldId="+store.worldId+" regular shutdown");
            store=null;service=null;currentSave=null;observedChunks.clear();observed.clear();measured.clear();lights=new LightQueries();
        } catch(Exception failure) {error("world shutdown",failure);}
    }
    public static WorldTime clock() {
        GameTime time=GameTime.getInstance();
        double hour=time.getTimeOfDay();
        return new WorldTime(time.getWorldAgeHours(),LocalDate.of(time.getYear(),time.getMonth()+1,time.getDayPlusOne()).atStartOfDay().plusNanos(Math.round(hour*3_600_000_000_000d)));
    }
    private static void log(String message) {System.out.println("[B42Foraging] "+message);}
    private static void error(String context,Exception failure) {if(!failureLogged){failureLogged=true;log("ERROR "+context+": "+failure);}}
}
