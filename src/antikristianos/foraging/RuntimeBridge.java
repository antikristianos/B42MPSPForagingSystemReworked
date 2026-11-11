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
import zombie.network.GameClient;

/** Native resource-zone integration. Regular Vanilla findings remain active during zone validation. */
@Exposer.LuaClass(name="B42ForagingBridge")
public final class RuntimeBridge {
    private RuntimeBridge() {}
    private static JsonStockStore store;
    private static StockService service;
    private static NativeZones nativeZones;
    private static ZoneCatalog catalog;
    private static KahluaTable searchRules;
    private static SearchPreview search;
    private static Path currentSave;
    private static WorldTime lastTime;
    private static LightQueries lights=new LightQueries();
    private static final Set<StockService.Chunk> observedChunks=new HashSet<>();
    private static final Set<Integer> measured=new HashSet<>();
    private static final Map<Integer,Long> lightRejectLogs=new LinkedHashMap<>();
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
            lights=new LightQueries();lightRejectLogs.clear();observedChunks.clear();observed.clear();measured.clear();failureLogged=false;nativeZones=null;catalog=null;search=null;
            log("WORLD_READY version="+Main.VERSION+" worldId="+store.worldId+" anchor="+store.anchor.hours()+" namespace="+JsonStockStore.NAMESPACE);
            return true;
        } catch(Exception failure) {error("world initialization",failure);return false;}
    }
    public static void setSearchRules(KahluaTable rules){if(!GameClient.client&&rules!=null&&rules!=searchRules){searchRules=rules;search=null;lights=new LightQueries();log("SEARCH_RULES_READY version="+Main.VERSION+" (preview)");}}
    public static void setSearchState(IsoPlayer player,boolean enabled,String focus){
        if(GameClient.client||player==null)return;
        if(!enabled){cancelSearch(player);return;}
        observePlayer(player);
        if(catalog!=null&&searchRules!=null){if(search==null){search=new SearchPreview(nativeZones,catalog,service,store,lights,searchRules);log("SEARCH_PREVIEW_READY worldId="+store.worldId);}search.control(player,true,focus,System.nanoTime()/1_000_000);}
    }
    public static void observePlayer(IsoPlayer player) {
        if(player==null||!beginWorld()) return;
        try {
            int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
            observedChunks.add(StockService.Chunk.at(x,y));
            if(nativeZones==null) {
                nativeZones=new NativeZones(IsoWorld.instance.getMetaGrid(),IsoWorld.instance.getBiomeMap(),NativeZones.definitions());
                catalog=new ZoneCatalog(store,service,nativeZones,new ZoneCatalog.Listener(){
                    public void ready(ZoneStock stock,boolean loaded){long total=stock.categories.values().stream().mapToLong(value->value.base).sum();log("ZONE_READY id="+stock.id+" biome="+stock.biome+" area="+stock.area+" base="+total+" categories="+stock.categories.size()+" loaded="+loaded+" source="+stock.source+" (stock only; Vanilla finds retained)");}
                    public void failed(String zone,Exception failure){log("ZONE_BLOCKED id="+zone+" reason="+failure);}
                });
            }
            int rank=0;
            for(ZoneCatalog.Shape zone:nativeZones.at(x,y)) {
                catalog.request(zone,StockService.Chunk.at(x,y));
                if(observed.add(zone.key()))log("ZONE_OBSERVED rank="+rank+" biome="+zone.biome()+" residual="+zone.residual()+" geometry="+zone.geometry());
                rank++;
            }
            lastTime=clock();
        } catch(Exception failure) {error("native zone observation",failure);}
    }
    public static void tick() {
        if(GameClient.client||store==null)return;
        try {
            lastTime=clock();
            if(catalog!=null)catalog.pump(lastTime,2048);
            service.working(lastTime);
            if(search!=null)try{search.tick(System.nanoTime()/1_000_000);}catch(Exception failure){search=null;lights=new LightQueries();error("search preview",failure);}
        }catch(Exception failure){error("zone stock update",failure);}
    }
    public static KahluaTable requestLight(IsoPlayer player) {
        if(player==null||!beginWorld()) return null;
        if(searchRules!=null)return search==null?null:search.request(player);
        int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
        long now=System.nanoTime()/1_000_000;
        if(lights.peek(player.getOnlineID())==null||now>=lights.peek(player.getOnlineID()).deadline())lights.request(player.getOnlineID(),x,y,z,now);
        LightQueries.Request request=lights.nextSend(player.getOnlineID(),now);
        if(request==null) return null;
        return lightData(request,lights.sendCount(player.getOnlineID()));
    }
    static KahluaTable lightData(LightQueries.Request request,int attempt){
        KahluaTable data=LuaManager.platform.newTable();
        data.rawset("requestId",request.id());data.rawset("session",request.session());data.rawset("onlineId",(double)request.player());
        data.rawset("protocol",(double)LightQueries.PROTOCOL);data.rawset("attempt",(double)attempt);
        data.rawset("x",(double)request.x());data.rawset("y",(double)request.y());data.rawset("z",(double)request.z());
        data.rawset("targetX",(double)request.targetX());data.rawset("targetY",(double)request.targetY());data.rawset("targetZ",(double)request.targetZ());
        return data;
    }
    private static void lightRejected(int player,String reason){
        long now=System.nanoTime()/1_000_000;Long previous=lightRejectLogs.get(player);if(previous!=null&&now-previous<2000)return;
        if(lightRejectLogs.size()>=128&&!lightRejectLogs.containsKey(player))lightRejectLogs.remove(lightRejectLogs.keySet().iterator().next());
        lightRejectLogs.put(player,now);var request=lights.peek(player);
        log("LIGHT_REPLY player="+player+" request="+(request==null?"none":request.id())+" status="+reason);
    }
    public static boolean acceptLight(IsoPlayer player,KahluaTable data) {
        if(GameClient.client||store==null||player==null||data==null) return false;
        int onlineId=player.getOnlineID();
        Object value=data.rawget("light"),id=data.rawget("requestId"),session=data.rawget("session");
        if(!(id instanceof String requestId)||!(session instanceof String responseSession)){lightRejected(onlineId,"malformed_reply");return false;}
        Object status=data.rawget("status");
        if(status instanceof String code&&!code.equals("ok")){
            String reason=Set.of("search_ended","out_of_range","wrong_floor","missing_square","invalid_light","malformed_request","unsupported_protocol").contains(code)?"client_"+code:"invalid_status";
            lightRejected(onlineId,lights.matches(onlineId,requestId,responseSession)?reason:"unbound_status");return false;
        }
        if(!(value instanceof Number number)){lightRejected(onlineId,"malformed_reply");return false;}
        int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
        var request=lights.peek(onlineId);
        String reason=lights.acceptReason(onlineId,requestId,responseSession,x,y,z,number.doubleValue(),System.nanoTime()/1_000_000);
        if(!reason.equals("accepted")){lightRejected(onlineId,reason);return false;}
        if(search!=null)try{if(!search.accepted(player,requestId,number.doubleValue(),System.nanoTime()/1_000_000)){lightRejected(onlineId,"search_context_changed");return false;}}catch(Exception failure){search=null;lights=new LightQueries();error("search light result",failure);return false;}
        log("LIGHT_ACCEPTED onlineId="+onlineId+" request="+requestId+" value="+number.doubleValue()+" at="+request.targetX()+","+request.targetY()+","+request.targetZ()+" from="+x+","+y+","+z+" (measurement only)");
        return true;
    }
    public static void observeLocalLight(IsoPlayer player) {
        if(GameClient.client||zombie.network.GameServer.server||player==null||!beginWorld()) return;
        var square=player.getCurrentSquare();if(square==null) return;
        int index=player.getPlayerNum();if(index<0||index>=4) return;
        float value=square.getLightLevel(index);
        if(Float.isFinite(value)&&value>=0&&value<=1&&measured.add(index)) log("LIGHT_LOCAL player="+index+" value="+value+" (measurement only)");
    }
    public static String lightDiagnostic(IsoPlayer player,zombie.iso.IsoGridSquare square,int index) {
        if(zombie.network.GameServer.server)return "diagnostic=server_not_client_light";
        try{return LightDiagnostics.describe(player,square,index);}
        catch(RuntimeException failure){return "diagnostic=unavailable error="+failure.getClass().getSimpleName();}
    }
    public static void cancelSearch(IsoPlayer player) {if(!GameClient.client&&player!=null){lights.cancel(player.getOnlineID());if(search!=null)search.control(player,false,null,System.nanoTime()/1_000_000);}}
    public static void chunkUnloading(IsoChunk chunk) {
        chunkHookCalls++;
        if(GameClient.client||store==null||chunk==null) return;
        try {
            StockService.Chunk key=new StockService.Chunk(chunk.wx,chunk.wy);
            if(catalog!=null)catalog.unload(key);
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
            store=null;service=null;catalog=null;nativeZones=null;search=null;currentSave=null;observedChunks.clear();observed.clear();measured.clear();lightRejectLogs.clear();lights=new LightQueries();
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
