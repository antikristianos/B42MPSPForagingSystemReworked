package antikristianos.foraging;

import java.io.IOException;
import java.util.*;

/** Session-only find identities and pickup locks are separate from persistent stock. */
public final class StockService {
    public record Chunk(int x,int y) {
        public static Chunk at(int x,int y) {return new Chunk(Math.floorDiv(x,8),Math.floorDiv(y,8));}
    }
    public static final class Find {
        public final String id,zone,category,item,finder;
        public final int x,y,z,count;
        public final double expires;
        public String owner,action;
        private Find(String id,String zone,String category,String item,int count,String finder,int x,int y,int z,double expires) {
            this.id=id;this.zone=zone;this.category=category;this.item=item;this.count=count;this.finder=finder;this.x=x;this.y=y;this.z=z;this.expires=expires;
        }
        public double alpha(double now) {return Math.max(0,Math.min(1,(expires-now-1d/60)/(4d/60)));}
        public boolean canStart(double now) {return now<expires-1d/60;}
    }
    public interface Issuer {boolean give(Find find);}
    private final JsonStockStore store;
    private final Map<String,ZoneStock> zones=new LinkedHashMap<>();
    private final Map<String,Find> finds=new LinkedHashMap<>();
    private final Map<Chunk,Set<String>> chunkZones=new HashMap<>();
    public StockService(JsonStockStore store) {this.store=store;}
    public void attach(ZoneStock zone) {
        if(zones.putIfAbsent(zone.id,zone)!=null) throw new IllegalStateException("duplicate active zone");
    }
    public ZoneStock zone(String id) {return zones.get(id);}
    public void touch(String zoneId,Chunk chunk) {
        if(!zones.containsKey(zoneId)) throw new IllegalArgumentException("unknown active zone");
        chunkZones.computeIfAbsent(chunk,key->new LinkedHashSet<>()).add(zoneId);
    }
    public Find reveal(String zoneId,String category,String item,int count,String finder,int x,int y,int z,WorldTime now) {
        if(item==null||item.isBlank()||count<1||count>10000||finder==null) throw new IllegalArgumentException("find data");
        ZoneStock zone=Objects.requireNonNull(zones.get(zoneId));zone.advance(now);
        ZoneStock.Category pool=zone.categories.get(category);
        if(pool==null||!pool.reserve()) return null;
        Find find=new Find(UUID.randomUUID().toString(),zoneId,category,item,count,finder,x,y,z,now.hours()+1);
        finds.put(find.id,find);return find;
    }
    public boolean start(String id,String player,String action,WorldTime now) {
        expire(now);
        Find find=finds.get(id);
        if(find==null||!find.canStart(now.hours())||player==null||action==null) return false;
        if(find.owner!=null) return find.owner.equals(player)&&find.action.equals(action);
        find.owner=player;find.action=action;return true;
    }
    public boolean cancel(String id,String player,String action) {
        Find find=finds.get(id);
        if(find==null||!Objects.equals(find.owner,player)||!Objects.equals(find.action,action)||find.owner==null) return false;
        find.owner=null;find.action=null;return true;
    }
    public boolean complete(String id,String player,String action,WorldTime now,Issuer issuer) {
        expire(now);Find find=finds.get(id);
        if(find==null||find.owner==null||!find.owner.equals(player)||!find.action.equals(action)) return false;
        if(!issuer.give(find)) return false; // issuer must not throw after a successful issue; no cross-inventory crash transaction
        zones.get(find.zone).categories.get(find.category).consume();finds.remove(id);return true;
    }
    public void disconnected(String player) {for(Find find:finds.values()) if(Objects.equals(find.owner,player)) {find.owner=null;find.action=null;}}
    public void expire(WorldTime now) {
        for(ZoneStock zone:zones.values()) zone.advance(now);
        for(Find find:new ArrayList<>(finds.values())) if(now.hours()>=find.expires) release(find);
    }
    private void release(Find find) {zones.get(find.zone).categories.get(find.category).release();finds.remove(find.id);}
    public void unload(Chunk chunk,WorldTime now) throws IOException {
        expire(now);Set<String> affected=new LinkedHashSet<>(chunkZones.getOrDefault(chunk,Set.of()));
        for(Find find:new ArrayList<>(finds.values())) if(Chunk.at(find.x,find.y).equals(chunk)) {affected.add(find.zone);release(find);}
        for(String id:affected) store.save(zones.get(id));
        chunkZones.remove(chunk);
    }
    public void shutdown(WorldTime now) throws IOException {
        expire(now);for(Find find:new ArrayList<>(finds.values())) release(find);
        for(ZoneStock zone:zones.values()) store.save(zone);
    }
    public int openFinds() {return finds.size();}
    public static Map<String,Double> focus(Map<String,Double> weights,String focus,double bonus,boolean available) {
        if(!Double.isFinite(bonus)||bonus<0) throw new IllegalArgumentException("focus bonus");
        double total=weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if(weights.values().stream().anyMatch(value->!Double.isFinite(value)||value<0)||!Double.isFinite(total)) throw new IllegalArgumentException("weights");
        Map<String,Double> result=new LinkedHashMap<>();
        if(total==0) return result;
        double original=weights.getOrDefault(focus,0d)/total;
        double adjusted=available&&original>0?Math.min(1,original+bonus/100):original;
        weights.forEach((name,value)->result.put(name,name.equals(focus)?adjusted:(original==1?0:value/total*(1-adjusted)/(1-original))));return result;
    }
    /** The caller supplies each zone's category/item attempt; first success stops all lower levels. */
    public static <T> T cascade(List<java.util.function.Supplier<T>> levels) {for(var level:levels) {T found=level.get();if(found!=null) return found;}return null;}
}
