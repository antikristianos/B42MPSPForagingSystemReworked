package antikristianos.foraging;

import java.util.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.CharacterTimedActions.LuaTimedActionNew;
import zombie.core.NetTimedAction;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/** Authoritative session finds; item bundles never travel from a client to the issuer. */
public final class FindRuntime {
    private static final class Peer {
        final IsoPlayer player;String nonce;long heartbeat,sequence;boolean enabled,reset=true;
        final Map<String,String> sent=new LinkedHashMap<>();
        Peer(IsoPlayer player){this.player=player;}
    }
    private static final class Bundle {
        final StockService.Find find;final ArrayList<InventoryItem> items;
        Bundle(StockService.Find find,ArrayList<InventoryItem> items){this.find=find;this.items=items;}
    }
    private record Lock(IsoPlayer player,String id,String token,KahluaTable action) {}
    private final StockService service;private KahluaTable rules;
    private final java.util.function.Function<StockService.Find,zombie.iso.IsoGridSquare> squares;
    private final String epoch=UUID.randomUUID().toString();
    private final Map<Integer,Peer> peers=new LinkedHashMap<>();
    private final Map<String,Bundle> bundles=new LinkedHashMap<>();
    private final Map<StockService.Chunk,Set<String>> spatial=new HashMap<>();
    private final IdentityHashMap<KahluaTable,Lock> locks=new IdentityHashMap<>();
    private long nextMaintenance;
    public FindRuntime(StockService service,KahluaTable rules){this(service,rules,find->IsoWorld.instance.currentCell.getGridSquare(find.x,find.y,find.z));}
    FindRuntime(StockService service,KahluaTable rules,java.util.function.Function<StockService.Find,zombie.iso.IsoGridSquare> squares){this.service=service;this.rules=rules;this.squares=squares;}
    public void setRules(KahluaTable rules){this.rules=Objects.requireNonNull(rules);}
    static int id(IsoPlayer player){return GameServer.server?player.getOnlineID():player.getPlayerNum();}
    private static String owner(IsoPlayer player){return Integer.toString(id(player));}
    private Object call(String name,Object... args){var r=LuaManager.caller.protectedCall(LuaManager.thread,rules.rawget(name),args);if(!r.isSuccess())throw new IllegalStateException("find rule "+name+": "+r);return r.isEmpty()?null:r.getFirst();}
    private static KahluaTable table(){return LuaManager.platform.newTable();}
    public void control(IsoPlayer player,boolean enabled,String nonce,long now){
        if(player==null||nonce==null||nonce.isBlank()||nonce.length()>64||nonce.chars().anyMatch(Character::isISOControl))return;
        int id=id(player);Peer peer=peers.get(id);
        if(peer==null||peer.player!=player){if(peers.size()>=128)return;peer=new Peer(player);peers.put(id,peer);}
        if(!nonce.equals(peer.nonce)){peer.nonce=nonce;peer.sent.clear();peer.reset=true;}
        peer.enabled=enabled;peer.heartbeat=now;
    }
    private boolean active(Peer peer,long now){return peer.enabled&&now-peer.heartbeat<=10_000&&!peer.player.isDead()&&(!GameServer.server||GameServer.Players.contains(peer.player));}
    public boolean discover(IsoPlayer player,String zone,String category,String item,int x,int y,int z,double distance){
        if(service.openFinds()>=4096)return false;
        Object materialized=call("materialize",player,item);
        if(!(materialized instanceof ArrayList<?> list)||list.isEmpty()||list.size()>10000)return false;
        ArrayList<InventoryItem> items=new ArrayList<>();Set<InventoryItem> unique=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object value:list){if(!(value instanceof InventoryItem object)||object.getContainer()!=null||object.getWorldItem()!=null||!unique.add(object))return false;items.add(object);}
        WorldTime time=RuntimeBridge.clock();StockService.Find find=service.reveal(zone,category,item,items.size(),owner(player),x,y,z,time);if(find==null)return false;
        var chunk=StockService.Chunk.at(x,y);service.touch(zone,chunk);bundles.put(find.id,new Bundle(find,items));spatial.computeIfAbsent(chunk,key->new LinkedHashSet<>()).add(find.id);
        // A notification failure must never replay this already-reserved discovery.
        try{Object award=call("award",player,item,GameServer.server?Math.min(20,Math.max(1,distance)):Math.max(1,distance));if(award instanceof KahluaTable detail)System.out.println("[B42Foraging] FINDER_XP id="+find.id+" finder="+owner(player)+" before="+detail.rawget("before")+" after="+detail.rawget("after")+" distance="+detail.rawget("distance"));}
        catch(RuntimeException failure){System.out.println("[B42Foraging] XP_ERROR find="+find.id+" "+failure);}
        var pool=service.zone(zone).categories.get(category);
        System.out.println("[B42Foraging] FIND_REVEALED id="+find.id+" finder="+owner(player)+" category="+category+" item="+item+" count="+items.size()+" expires="+find.expires+" at="+x+","+y+","+z+" available="+pool.available+" reserved="+pool.reserved);
        return true;
    }
    private boolean reachable(IsoPlayer player,StockService.Find find){
        if(player==null||find==null||player.isDead()||player.getVehicle()!=null||player.getCurrentSquare()==null||player.getZ()!=find.z||Math.abs(Math.floor(player.getX())-find.x)>1||Math.abs(Math.floor(player.getY())-find.y)>1)return false;
        var square=squares.apply(find);
        return square!=null&&!square.isBlockedTo(player.getCurrentSquare())&&Boolean.TRUE.equals(call("position",player,square));
    }
    private boolean nativeAction(IsoPlayer player,KahluaTable action){
        if(action==null||action.rawget("character")!=player)return false;
        Object nativeAction=action.rawget(GameServer.server?"netAction":"action");
        return GameServer.server?nativeAction instanceof NetTimedAction network&&network.action==action:nativeAction instanceof LuaTimedActionNew local&&local.getTable()==action;
    }
    public boolean valid(IsoPlayer player,String id,KahluaTable action){
        StockService.Find find=service.find(id);if(!reachable(player,find))return false;
        Lock lock=locks.get(action);
        if(lock!=null)return lock.player==player&&lock.id.equals(id)&&Objects.equals(find.action,lock.token)&&Objects.equals(find.owner,owner(player))&&RuntimeBridge.clock().hours()<find.expires;
        Peer peer=peers.get(id(player));return peer!=null&&active(peer,System.nanoTime()/1_000_000)&&peer.sent.containsKey(id)&&find.owner==null&&find.canStart(RuntimeBridge.clock().hours());
    }
    public boolean start(IsoPlayer player,String id,KahluaTable action){
        if(!nativeAction(player,action)||!valid(player,id,action))return false;
        Lock existing=locks.get(action);if(existing!=null)return true;
        String token=UUID.randomUUID().toString();if(!service.start(id,owner(player),token,RuntimeBridge.clock()))return false;
        locks.put(action,new Lock(player,id,token,action));System.out.println("[B42Foraging] PICKUP_LOCK id="+id+" player="+owner(player));return true;
    }
    public void cancel(IsoPlayer player,String id,KahluaTable action){
        Lock lock=locks.get(action);if(lock==null||lock.player!=player||!lock.id.equals(id))return;
        service.cancel(id,owner(player),lock.token);locks.remove(action);System.out.println("[B42Foraging] PICKUP_RELEASE id="+id+" player="+owner(player));
    }
    public boolean complete(IsoPlayer player,String id,KahluaTable action,ItemContainer requested){
        if(!nativeAction(player,action)||!valid(player,id,action))return false;
        Lock lock=locks.get(action);Bundle bundle=bundles.get(id);if(lock==null||bundle==null)return false;
        ItemContainer target=requested;
        if(target==null)target=player.getInventory();
        if(target!=player.getInventory()&&!target.isInCharacterInventory(player)){
            var square=target.getSourceGrid();if(square==null&&target.getParent()!=null)square=target.getParent().getSquare();
            if(square==null||square.getZ()!=player.getZ()||Math.hypot(player.getX()-square.getX(),player.getY()-square.getY())>2||square.isBlockedTo(player.getCurrentSquare())||!Boolean.TRUE.equals(call("position",player,square)))return false;
        }
        final ItemContainer destination=target;
        // The native engine owns MP action duration. A direct early invocation cannot issue items.
        if(GameServer.server&&((NetTimedAction)action.rawget("netAction")).getProgress()<.999f)return false;
        boolean success=service.complete(id,owner(player),lock.token,RuntimeBridge.clock(),find->{
            if(!Boolean.TRUE.equals(call("canIssue",player,destination,bundle.items)))return false;
            return ItemBatch.add(destination,bundle.items);
        });
        if(success){
            locks.remove(action);removeBundle(id);
            try{call("issued",player,destination,bundle.items);}catch(RuntimeException failure){System.out.println("[B42Foraging] ISSUE_SYNC_ERROR id="+id+" canonical_inventory_committed=true "+failure);}
            System.out.println("[B42Foraging] PICKUP_COMPLETE id="+id+" player="+owner(player)+" count="+bundle.items.size()+" stockUnits=1");
        }else cancel(player,id,action);
        return success;
    }
    public void tick(){
        long now=System.nanoTime()/1_000_000;if(now<nextMaintenance)return;nextMaintenance=now+250;
        for(Lock lock:new ArrayList<>(locks.values()))if(service.find(lock.id)==null||lock.player.isDead()||!nativeAction(lock.player,lock.action)||GameServer.server&&!GameServer.Players.contains(lock.player))cancel(lock.player,lock.id,lock.action);
        for(Bundle bundle:new ArrayList<>(bundles.values()))if(service.find(bundle.find.id)==null){removeBundle(bundle.find.id);System.out.println("[B42Foraging] FIND_RELEASED id="+bundle.find.id+" reason="+(RuntimeBridge.clock().hours()>=bundle.find.expires?"expired":"chunk_unloaded"));}
        peers.values().removeIf(peer->GameServer.server&&!GameServer.Players.contains(peer.player));
    }
    private void removeBundle(String id){Bundle bundle=bundles.remove(id);if(bundle==null)return;var chunk=StockService.Chunk.at(bundle.find.x,bundle.find.y);Set<String> ids=spatial.get(chunk);if(ids!=null){ids.remove(id);if(ids.isEmpty())spatial.remove(chunk);}}
    public KahluaTable messages(IsoPlayer player){
        Peer peer=peers.get(id(player));if(peer==null||peer.player!=player)return null;
        long now=System.nanoTime()/1_000_000;boolean active=active(peer,now);
        double radius=active?((Number)call("radius",player)).doubleValue():0;
        Map<String,String> current=new LinkedHashMap<>();KahluaTable changed=table(),removed=table();int additions=0,deletions=0;
        double reach=Math.min(30,Math.max(3,radius));
        Set<String> candidates=new LinkedHashSet<>();
        if(active&&Double.isFinite(reach))for(int y=Math.floorDiv((int)Math.floor(player.getY()-reach),8);y<=Math.floorDiv((int)Math.floor(player.getY()+reach),8);y++)for(int x=Math.floorDiv((int)Math.floor(player.getX()-reach),8);x<=Math.floorDiv((int)Math.floor(player.getX()+reach),8);x++)candidates.addAll(spatial.getOrDefault(new StockService.Chunk(x,y),Set.of()));
        for(String id:candidates){
            StockService.Find find=service.find(id);
            if(find==null||find.z!=player.getZ()||Math.hypot(player.getX()-find.x,player.getY()-find.y)>reach)continue;
            String state=Objects.toString(find.owner,"")+":"+Objects.toString(find.action,"");current.put(find.id,state);
            if(peer.reset||!state.equals(peer.sent.get(find.id))){Bundle bundle=bundles.get(find.id);if(bundle!=null)changed.rawset((double)++additions,record(find,bundle));}
        }
        for(String id:peer.sent.keySet())if(!current.containsKey(id))removed.rawset((double)++deletions,id);
        if(!peer.reset&&additions==0&&deletions==0)return null;
        KahluaTable data=table();data.rawset("epoch",epoch);data.rawset("nonce",peer.nonce);data.rawset("sequence",(double)++peer.sequence);data.rawset("onlineId",(double)id(player));data.rawset("reset",peer.reset);data.rawset("finds",changed);data.rawset("removed",removed);
        peer.sent.clear();peer.sent.putAll(current);peer.reset=false;return data;
    }
    private static KahluaTable record(StockService.Find find,Bundle bundle){
        KahluaTable data=table();data.rawset("id",find.id);data.rawset("x",(double)find.x);data.rawset("y",(double)find.y);data.rawset("z",(double)find.z);data.rawset("itemType",find.item);data.rawset("category",find.category);data.rawset("count",(double)find.count);data.rawset("expires",find.expires);data.rawset("busyBy",find.owner==null?null:Double.valueOf(find.owner));
        Map<String,KahluaTable> groups=new LinkedHashMap<>();
        for(InventoryItem item:bundle.items){
            var food=item instanceof zombie.inventory.types.Food f?f:null;
            String key=item.getFullType()+":"+(food==null?"":food.getPoisonPower()+":"+food.getPoisonDetectionLevel());
            KahluaTable entry=groups.get(key);if(entry==null){entry=table();entry.rawset("type",item.getFullType());entry.rawset("count",0d);if(food!=null){entry.rawset("poisonPower",(double)food.getPoisonPower());entry.rawset("poisonDetectionLevel",(double)food.getPoisonDetectionLevel());}groups.put(key,entry);}
            entry.rawset("count",((Number)entry.rawget("count")).doubleValue()+1);
        }
        KahluaTable items=table();int index=0;for(KahluaTable entry:groups.values())items.rawset((double)++index,entry);data.rawset("items",items);return data;
    }
}
