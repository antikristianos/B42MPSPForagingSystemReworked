package antikristianos.foraging;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerLOS;

/** Productive native search inputs, deliberately no reveal/reservation/XP before world validation. */
public final class SearchPreview {
    private static final class Entry {
        final IsoPlayer player;final SearchCadence cadence;String focus="None",trialFocus="None";long heartbeat,nextCheck,trial,deadline;Probe probe;
        Entry(IsoPlayer player,long now){this.player=player;cadence=new SearchCadence(player.getX(),player.getY(),level(player),now,bound->ThreadLocalRandom.current().nextInt(bound));}
    }
    private static final class Probe {
        final int x,y,z;final List<ZoneCatalog.Shape> zones;LightQueries.Request light;int sends;
        Probe(IsoGridSquare square,List<ZoneCatalog.Shape> zones){x=square.getX();y=square.getY();z=square.getZ();this.zones=zones;}
    }
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final NativeZones nativeZones;private final ZoneCatalog catalog;private final StockService stocks;private final JsonStockStore store;private final LightQueries lights;private final KahluaTable rules;
    private int cursor;
    public SearchPreview(NativeZones nativeZones,ZoneCatalog catalog,StockService stocks,JsonStockStore store,LightQueries lights,KahluaTable rules){this.nativeZones=nativeZones;this.catalog=catalog;this.stocks=stocks;this.store=store;this.lights=lights;this.rules=rules;}
    private static int id(IsoPlayer player){return GameServer.server?player.getOnlineID():player.getPlayerNum();}
    private static int level(IsoPlayer player){return player.getPerkLevel(PerkFactory.Perks.PlantScavenging);}
    static boolean withinRadius(double playerX,double playerY,int targetX,int targetY,double radius){return Double.isFinite(radius)&&radius>=0&&Math.hypot(playerX-targetX,playerY-targetY)<=radius;}
    public void control(IsoPlayer player,boolean enabled,String focus,long now){
        if(GameClient.client||player==null)return;int id=id(player);
        if(!enabled){if(entries.containsKey(id))System.out.println("[B42Foraging] SEARCH_STATE player="+id+" enabled=false");cancel(id,"search_ended");return;}
        if(!entries.containsKey(id)&&entries.size()>=128)return;
        Entry entry=entries.get(id);boolean starting=entry==null||entry.player!=player;if(starting){lights.cancel(id);entry=new Entry(player,now);entries.put(id,entry);}
        String previous=entry.focus;
        entry.heartbeat=now;entry.focus=focus!=null&&focus.length()<=64&&focus.chars().noneMatch(Character::isISOControl)?focus:"None";
        if(starting||!previous.equals(entry.focus))System.out.println("[B42Foraging] SEARCH_STATE player="+id+" enabled=true focus="+entry.focus.replaceAll("\\s","_")+" from="+player.getX()+","+player.getY()+","+player.getZ());
    }
    private void cancel(int id,String reason){var entry=entries.remove(id);if(entry!=null&&entry.probe!=null&&entry.probe.light!=null)System.out.println("[B42Foraging] LIGHT_CANCEL player="+id+" request="+entry.probe.light.id()+" reason="+reason);lights.cancel(id);}
    private static boolean nearby(IsoPlayer player,Probe probe){return LightQueries.inRange((int)Math.floor(player.getX()),(int)Math.floor(player.getY()),(int)Math.floor(player.getZ()),probe.x,probe.y,probe.z);}
    private Object call(String name,Object... arguments){
        Object function=rules.rawget(name);if(function==null)throw new IllegalStateException("missing search rule "+name);
        var value=LuaManager.caller.protectedCall(LuaManager.thread,function,arguments);
        if(!value.isSuccess())throw new IllegalStateException("search rule "+name+": "+value);
        return value.getFirst();
    }
    private static double finite(Object value){if(value instanceof Number n&&Double.isFinite(n.doubleValue()))return n.doubleValue();throw new IllegalStateException("invalid numeric search rule");}
    private boolean active(Entry entry,long now){return now-entry.heartbeat<=10_000&&(!GameServer.server||GameServer.Players.contains(entry.player))&&!entry.player.isDead()&&Boolean.TRUE.equals(call("enabled",entry.player));}
    private boolean visible(IsoPlayer player,IsoGridSquare square){
        if(square==null||player.getCurrentSquare()==null||square.getZ()!=player.getZ()||square.isBlockedTo(player.getCurrentSquare()))return false;
        if(!Boolean.TRUE.equals(call("position",player,square)))return false;
        if(square==player.getCurrentSquare())return true;
        return GameServer.server?ServerLOS.instance!=null&&ServerLOS.instance.isCouldSee(player,square):square.isCanSee(player.getPlayerNum());
    }
    public void tick(long now){
        if(GameClient.client||entries.isEmpty())return;
        var ids=new ArrayList<>(entries.keySet());
        for(int i=0;i<Math.min(8,ids.size());i++){
            int id=ids.get(Math.floorMod(cursor++,ids.size()));Entry entry=entries.get(id);if(entry==null||now<entry.nextCheck)continue;entry.nextCheck=now+250;
            if(!active(entry,now)){cancel(id,"inactive");continue;}
            if(entry.cadence.waiting()){
                if(entry.probe!=null&&!nearby(entry.player,entry.probe)){finish(entry,"position_changed",null,null,null,Double.NaN,now,false);continue;}
                if(now>=entry.deadline||(entry.probe!=null&&entry.probe.light!=null&&now>=entry.probe.light.deadline())){finish(entry,entry.probe!=null&&entry.probe.light!=null?"light_timeout":"zone_timeout",null,null,null,Double.NaN,now,false);continue;}
                if(entry.probe!=null&&entry.probe.light==null)prepareLight(entry,now);
                continue;
            }
            entry.trial=entry.cadence.opportunity(entry.player.getX(),entry.player.getY(),now);if(entry.trial==0)continue;
            entry.trialFocus=entry.focus;
            entry.deadline=now+15_000;
            if(GameServer.server&&ServerLOS.instance==null){finish(entry,"server_los_unavailable",null,null,null,Double.NaN,now,false);continue;}
            var player=entry.player;int x=(int)Math.floor(player.getX()),y=(int)Math.floor(player.getY()),z=(int)Math.floor(player.getZ());
            var home=nativeZones.at(x,y);if(home.isEmpty()){finish(entry,"no_zone",null,null,null,Double.NaN,now,false);continue;}
            double radius=Math.min(3,Math.max(0,finite(call("radius",player))));int r=(int)Math.ceil(radius);var candidates=new ArrayList<IsoGridSquare>();
            for(int dx=-r;dx<=r;dx++)for(int dy=-r;dy<=r;dy++)if(withinRadius(player.getX(),player.getY(),x+dx,y+dy,radius)&&home.get(0).contains().test(x+dx,y+dy)){
                var square=IsoWorld.instance.currentCell.getGridSquare(x+dx,y+dy,z);if(visible(player,square))candidates.add(square);
            }
            if(candidates.size()<2){finish(entry,"no_visible_position",null,null,null,Double.NaN,now,false);continue;}
            var square=candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));var zones=nativeZones.at(square.getX(),square.getY());
            entry.probe=new Probe(square,zones);
            for(var shape:zones)catalog.request(shape,StockService.Chunk.at(square.getX(),square.getY()));
            prepareLight(entry,now);
        }
    }
    private void prepareLight(Entry entry,long now){
        Probe probe=entry.probe;if(probe.zones.stream().anyMatch(shape->!catalog.ready(shape)))return;
        var square=IsoWorld.instance.currentCell.getGridSquare(probe.x,probe.y,probe.z);
        if(!visible(entry.player,square)||!nearby(entry.player,probe)){finish(entry,"position_changed",null,null,null,Double.NaN,now,false);return;}
        if(GameServer.server){
            var p=entry.player;probe.light=lights.requestTarget(id(p),(int)Math.floor(p.getX()),(int)Math.floor(p.getY()),(int)Math.floor(p.getZ()),probe.x,probe.y,probe.z,now);
            if(probe.light==null)throw new IllegalStateException("light context already pending");
        }else {System.out.println("[B42Foraging] LIGHT_SAMPLE request=local trial="+entry.trial+" "+RuntimeBridge.lightDiagnostic(entry.player,square,entry.player.getPlayerNum()));evaluate(entry,square.getLightLevel(entry.player.getPlayerNum()),now);}
    }
    public KahluaTable request(IsoPlayer player){
        Entry entry=entries.get(id(player));if(entry==null||entry.probe==null||entry.probe.light==null)return null;
        long now=System.nanoTime()/1_000_000;
        if(!active(entry,now)){cancel(id(player),"inactive");return null;}
        if(now>=entry.deadline||!nearby(player,entry.probe))return null;
        var request=lights.nextSend(id(player),now);if(request==null)return null;
        int attempt=lights.sendCount(id(player));
        entry.probe.sends=attempt;
        System.out.println("[B42Foraging] LIGHT_REQUEST player="+id(player)+" request="+request.id()+" attempt="+attempt+" trial="+entry.trial+" at="+request.targetX()+","+request.targetY()+","+request.targetZ()+" origin="+request.x()+","+request.y()+","+request.z()+" from="+player.getX()+","+player.getY()+","+player.getZ());
        return RuntimeBridge.lightData(request,attempt);
    }
    public boolean accepted(IsoPlayer player,String requestId,double light,long now){Entry entry=entries.get(id(player));if(entry==null||now>=entry.deadline||entry.probe==null||entry.probe.light==null||!entry.probe.light.id().equals(requestId)||!active(entry,now))return false;evaluate(entry,light,now);return true;}
    private void evaluate(Entry entry,double light,long now){
        if(!Double.isFinite(light)||light<0||light>1)return;
        Probe probe=entry.probe;var square=IsoWorld.instance.currentCell.getGridSquare(probe.x,probe.y,probe.z);
        if(!nearby(entry.player,probe)||!visible(entry.player,square)){finish(entry,"sight_changed",null,null,null,light,now,false);return;}
        var current=nativeZones.at(probe.x,probe.y);
        if(!current.stream().map(ZoneCatalog.Shape::key).toList().equals(probe.zones.stream().map(ZoneCatalog.Shape::key).toList())){finish(entry,"zone_changed",null,null,null,light,now,false);return;}
        for(var shape:current)catalog.request(shape,StockService.Chunk.at(probe.x,probe.y));
        SearchSelection.Result result=null;String zoneId=null,biome=null;
        for(var shape:probe.zones){
            if(!catalog.ready(shape)){finish(entry,"zone_changed",null,null,null,light,now,false);return;}
            zoneId=shape.id(store.worldId);biome=shape.biome();ZoneStock stock=stocks.zone(zoneId);
            double bonus=finite(call("focus",entry.player,biome,entry.trialFocus));
            String currentBiome=biome;
            result=SearchSelection.select(stock,nativeZones.definition(biome).weights(),entry.trialFocus,Math.max(0,bonus),()->ThreadLocalRandom.current().nextDouble(),new SearchSelection.Rules(){
                public String item(String category){Object value=call("item",currentBiome,category);return value instanceof String?(String)value:null;}
                public boolean valid(String item,String category){
                    Object value=call("inspect",entry.player,item,category,currentBiome,square,light);
                    if(!(value instanceof KahluaTable detail))throw new IllegalStateException("invalid item inspection");
                    StringBuilder message=new StringBuilder("[B42Foraging] SEARCH_ITEM player=").append(id(entry.player)).append(" trial=").append(entry.trial).append(" request=").append(probe.light==null?"none":probe.light.id()).append(" zone=").append(shape.id(store.worldId)).append(" category=").append(category).append(" item=").append(item);
                    for(String key:List.of("valid","reason","level","radius","distance","rawLight","effectiveLight","daylight","torch","outside","hour","rain","snow")){Object field=detail.rawget(key);if(field!=null)message.append(' ').append(key).append('=').append(field.toString().replaceAll("\\s","_"));}
                    System.out.println(message);return Boolean.TRUE.equals(detail.rawget("valid"));
                }
            });
            if(result.reason().equals("match"))break;
        }
        finish(entry,result==null?"no_zone":result.reason(),zoneId,biome,result,light,now,result!=null&&result.reason().equals("match"));
    }
    private void finish(Entry entry,String reason,String zoneId,String biome,SearchSelection.Result result,double light,long now,boolean matched){
        int x=entry.probe==null?(int)Math.floor(entry.player.getX()):entry.probe.x,y=entry.probe==null?(int)Math.floor(entry.player.getY()):entry.probe.y;
        System.out.println("[B42Foraging] SEARCH_PROBE trial="+entry.trial+" player="+id(entry.player)+" result="+reason+" focus="+entry.trialFocus+" focusChance="+(result==null?0:result.focusChance())+" category="+(result==null?null:result.category())+" item="+(result==null?null:result.item())+" biome="+biome+" zone="+zoneId+" from="+entry.player.getX()+","+entry.player.getY()+","+entry.player.getZ()+" at="+x+","+y+" facing="+entry.player.getLookAngleRadians()+" light="+(Double.isFinite(light)?Double.toString(light):"unavailable")+" request="+(entry.probe==null||entry.probe.light==null?"none":entry.probe.light.id())+" sends="+(entry.probe==null?0:entry.probe.sends)+" visibility="+(GameServer.server?"server_geometric_LOS":"local_canSee")+" (preview; stock and XP unchanged)");
        entry.cadence.finish(matched,x,y,level(entry.player),now);entry.probe=null;lights.cancel(id(entry.player));
    }
}
