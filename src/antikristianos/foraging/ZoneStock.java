package antikristianos.foraging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** No engine dependencies or player-owned pools. Mutated only by the authoritative thread. */
public final class ZoneStock {
    public static final class Category {
        public final long base;
        public final double phase;
        public long capacity,available,inactive,reserved;
        public double fraction;
        public Category(long base,double phase,long capacity,long available,long inactive,double fraction) {
            this.base=base;this.phase=phase;this.capacity=capacity;this.available=available;this.inactive=inactive;this.fraction=fraction;
            validate();
        }
        public void validate() {
            if(base<0||capacity<0||available<0||inactive<0||reserved<0||!Double.isFinite(phase)||phase<0||phase>=1||!Double.isFinite(fraction)||fraction<0||fraction>=1||available>Math.max(0,capacity-reserved)) throw new IllegalArgumentException("invalid category stock");
        }
        public void setCapacity(long next) {
            if(next<0) throw new IllegalArgumentException("capacity");
            capacity=next;
            long excess=Math.max(0,available-Math.max(0,capacity-reserved));
            available-=excess;inactive=Math.addExact(inactive,excess);
            if(available+reserved>=capacity) fraction=0;
            validate();
        }
        public void regenerate(double amount) {
            if(!Double.isFinite(amount)||amount<0) throw new IllegalArgumentException("regeneration");
            long room=Math.max(0,capacity-reserved-available);
            if(room==0) {fraction=0;return;}
            double accumulated=fraction+amount;
            if(accumulated>=room) {available+=room;inactive-=Math.min(inactive,room);fraction=0;}
            else {
                long gain=(long)Math.floor(accumulated);
                available+=gain;inactive-=Math.min(inactive,gain);fraction=accumulated-gain;
            }
            validate();
        }
        public boolean reserve() {if(available==0) return false;available--;reserved++;validate();return true;}
        public void consume() {if(reserved==0) throw new IllegalStateException("no reservation");reserved--;validate();}
        public void release() {
            if(reserved==0) throw new IllegalStateException("no reservation");
            reserved--;
            if(available+reserved<capacity) available++;else inactive++;
            validate();
        }
        public Category fixedCopy() {
            long units=Math.addExact(available,reserved);
            return new Category(base,phase,capacity,Math.min(capacity,units),Math.addExact(inactive,Math.max(0,units-capacity)),fraction);
        }
    }
    public final String id,biome,source,geometry;
    public final double area,biomePercent,totalPhase,splitPhase;
    public final Map<String,Category> categories;
    public WorldTime time;
    public long generation;
    public ZoneStock(String id,String biome,String source,String geometry,double area,double biomePercent,double totalPhase,double splitPhase,WorldTime time,Map<String,Category> categories,long generation) {
        if(id==null||!id.matches("[0-9a-f]{64}")||biome==null||biome.isBlank()||source==null||geometry==null||!Double.isFinite(area)||area<0||!Double.isFinite(biomePercent)||biomePercent<0||!phase(totalPhase)||!phase(splitPhase)||generation<0) throw new IllegalArgumentException("zone metadata");
        this.id=id;this.biome=biome;this.source=source;this.geometry=geometry;this.area=area;this.biomePercent=biomePercent;this.totalPhase=totalPhase;this.splitPhase=splitPhase;this.time=time;this.generation=generation;
        this.categories=Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        this.categories.values().forEach(Category::validate);
    }
    public static ZoneStock create(String id,String biome,String source,String geometry,double area,double min,double max,double abundance,double biomePercent,long seed,WorldTime anchor,Map<String,Double> weights) {
        if(!Double.isFinite(min)||!Double.isFinite(max)||min<0||max<0||!Double.isFinite(abundance)||abundance<0) throw new IllegalArgumentException("density");
        double totalPhase=unit(seed,id,"total"),splitPhase=unit(seed,id,"split");
        double ideal=area/2000*(min+max)/2*abundance;
        if(!Double.isFinite(ideal)||ideal>Integer.MAX_VALUE) throw new IllegalArgumentException("pool size");
        long total=(long)Math.floor(ideal+totalPhase);
        TreeMap<String,Double> valid=new TreeMap<>();
        weights.forEach((key,value)->{
            if(key==null||value==null||!Double.isFinite(value)||value<0) throw new IllegalArgumentException("category weights");
            if(value>0&&!key.equals("Tracks")) valid.put(key,value);
        });
        double sum=valid.values().stream().mapToDouble(Double::doubleValue).sum(),prefix=0;
        if(!Double.isFinite(sum)) throw new IllegalArgumentException("weight sum");
        Map<String,Category> categories=new LinkedHashMap<>();
        int index=0;long allocated=0;
        for(var entry:valid.entrySet()) {
            prefix+=total*entry.getValue()/sum;
            long cumulative=++index==valid.size()?total:(long)Math.floor(prefix+splitPhase);
            long base=cumulative-allocated;allocated=cumulative;
            double monthlyPhase=unit(seed,id,entry.getKey());
            long capacity=PoolProfiles.capacity(entry.getKey(),base,monthlyPhase,anchor.calendar().getMonthValue());
            categories.put(entry.getKey(),new Category(base,monthlyPhase,capacity,capacity,0,0));
        }
        return new ZoneStock(id,biome,source,geometry,area,biomePercent,totalPhase,splitPhase,anchor,categories,0);
    }
    public void advance(WorldTime now) {
        if(now.hours()<time.hours()) throw new IllegalArgumentException("world time moved backwards");
        while(now.hours()-time.hours()>1e-9) {
            int month=time.calendar().getMonthValue();
            double step=Math.min(now.hours()-time.hours(),time.untilMonthEnd());
            if(step<=0) throw new IllegalStateException("calendar step");
            for(var entry:categories.entrySet()) {
                Category stock=entry.getValue();
                stock.setCapacity(PoolProfiles.capacity(entry.getKey(),stock.base,stock.phase,month));
                stock.regenerate(stock.capacity*PoolProfiles.dailyFraction(entry.getKey(),biomePercent)*step/24);
            }
            time=time.plusHours(step);
        }
        // Apply the new month's cap even when exactly on the boundary; never refill at a cap increase.
        categories.forEach((name,stock)->stock.setCapacity(PoolProfiles.capacity(name,stock.base,stock.phase,now.calendar().getMonthValue())));
        time=now;
    }
    public ZoneStock fixedCopy() {
        Map<String,Category> copy=new LinkedHashMap<>();categories.forEach((key,value)->copy.put(key,value.fixedCopy()));
        return new ZoneStock(id,biome,source,geometry,area,biomePercent,totalPhase,splitPhase,time,copy,generation);
    }
    public static String hash(String value) {
        try {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public static double unit(long seed,String id,String purpose) {return Long.parseUnsignedLong(hash(seed+":"+id+":"+purpose).substring(0,13),16)/4503599627370496d;}
    private static boolean phase(double value) {return Double.isFinite(value)&&value>=0&&value<1;}
}
