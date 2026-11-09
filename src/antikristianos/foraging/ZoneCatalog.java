package antikristianos.foraging;

import java.io.IOException;
import java.util.*;

/** Authoritative, bounded preparation of complete resource zones, never per-player stock. */
public final class ZoneCatalog {
    @FunctionalInterface public interface Contains {boolean test(int x,int y);}
    public record Bounds(int x,int y,int width,int height) {
        public Bounds {if(width<1||height<1||(long)width*height>16_777_216L)throw new IllegalArgumentException("unsupported zone bounds");Math.addExact(x,width);Math.addExact(y,height);}
        public boolean contains(int px,int py){return px>=x&&py>=y&&px<(long)x+width&&py<(long)y+height;}
        public int size(){return Math.multiplyExact(width,height);}
    }
    public record Shape(String biome,String source,String geometry,Bounds bounds,Contains contains,boolean residual) {
        public Shape {Objects.requireNonNull(biome);Objects.requireNonNull(source);Objects.requireNonNull(geometry);Objects.requireNonNull(bounds);Objects.requireNonNull(contains);}
        public String key(){return biome+"\n"+source+"\n"+geometry;}
        public String id(String world){return ZoneStock.hash(world+"\n"+key());}
    }
    public record Definition(double min,double max,double abundance,double refill,Map<String,Double> weights) {
        public Definition {weights=Map.copyOf(weights);if(!Double.isFinite(min+max+abundance+refill)||min<0||max<min||abundance<0||refill<0)throw new IllegalArgumentException("invalid zone definition");}
    }
    /** Immutable native-data snapshot. Null biome is unknown, Water is explicitly excluded. */
    public interface View {
        String highest(int x,int y);
        String biome(int x,int y);
        String fingerprint();
    }
    public interface Source {
        View snapshot(Shape shape);
        Definition definition(String biome);
    }
    public interface Listener {void ready(ZoneStock stock,boolean loaded);void failed(String zone,Exception failure);}
    private static final class Pending {
        final Shape shape;final View view;final Set<StockService.Chunk> chunks=new LinkedHashSet<>();int cursor;long area;
        Pending(Shape shape,View view){this.shape=shape;this.view=view;}
    }
    private final JsonStockStore store;
    private final StockService service;
    private final Source source;
    private final Listener listener;
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private final Map<String,String> verified=new HashMap<>();
    private final Set<String> blocked=new HashSet<>();
    public ZoneCatalog(JsonStockStore store,StockService service,Source source,Listener listener){this.store=store;this.service=service;this.source=source;this.listener=listener;}
    public void request(Shape shape,StockService.Chunk chunk) {
        String id=shape.id(store.worldId);
        if(blocked.contains(id))return;
        if(service.zone(id)!=null&&!pending.containsKey(id)) {
            View current=source.snapshot(shape);
            if(Objects.equals(verified.get(id),current.fingerprint())){service.touch(id,chunk);return;}
            pending.put(id,new Pending(shape,current));
        }
        Pending job=pending.get(id);
        if(job==null){job=new Pending(shape,source.snapshot(shape));pending.put(id,job);}
        job.chunks.add(chunk);
    }
    /** At most budget tile evaluations in total, independent of the number of searching players. */
    public int pump(WorldTime now,int budget) {
        if(budget<1)throw new IllegalArgumentException("tile budget");
        int used=0;
        while(used<budget&&!pending.isEmpty()) {
            var entry=pending.entrySet().iterator().next();String id=entry.getKey();Pending job=entry.getValue();
            try {
                Bounds b=job.shape.bounds();
                while(used<budget&&job.cursor<b.size()) {
                    int x=b.x()+job.cursor%b.width(),y=b.y()+job.cursor/b.width();job.cursor++;used++;
                    if(!job.shape.contains().test(x,y)||"Water".equals(job.view.biome(x,y)))continue;
                    String highest=job.view.highest(x,y);
                    if(job.shape.residual()?highest==null&&job.shape.biome().equals(job.view.biome(x,y)):job.shape.key().equals(highest))job.area++;
                }
                if(job.cursor==b.size()) {
                    String current=source.snapshot(job.shape).fingerprint();
                    if(!current.equals(job.view.fingerprint())) {
                        Pending retry=new Pending(job.shape,source.snapshot(job.shape));retry.chunks.addAll(job.chunks);pending.put(id,retry);continue;
                    }
                    ZoneStock active=service.zone(id);ZoneStock stock=active==null?store.load(id):active;boolean loaded=stock!=null;
                    if(stock==null){
                        Definition def=source.definition(job.shape.biome());
                        stock=ZoneStock.create(id,job.shape.biome(),job.shape.source(),job.shape.geometry(),job.area,def.min(),def.max(),def.abundance(),def.refill(),store.seed,store.anchor,def.weights());
                    } else if(!stock.biome.equals(job.shape.biome())||!stock.source.equals(job.shape.source())||!stock.geometry.equals(job.shape.geometry())||stock.area!=job.area)throw new IOException("stored zone provenance/capacity area changed; preserving fixed stock");
                    stock.advance(now);store.working(stock);if(active==null)service.attach(stock);
                    for(var chunk:job.chunks)service.touch(id,chunk);
                    verified.put(id,current);pending.remove(id);if(active==null)listener.ready(stock,loaded);
                }
            }catch(Exception failure){pending.remove(id);blocked.add(id);listener.failed(id,failure);}
        }
        return used;
    }
    public void unload(StockService.Chunk chunk) {
        pending.values().removeIf(job->{job.chunks.remove(chunk);return job.chunks.isEmpty();});
    }
    public int pending(){return pending.size();}
    public int blocked(){return blocked.size();}
    public boolean ready(Shape shape){String id=shape.id(store.worldId);return !blocked.contains(id)&&!pending.containsKey(id)&&service.zone(id)!=null;}
}
