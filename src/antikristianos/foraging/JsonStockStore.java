package antikristianos.foraging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.*;

/** One fixed file per zone. Working files are deliberately never replayed. */
public final class JsonStockStore {
    public static final String NAMESPACE="B42MPSPForagingSystemReworked";
    private static final int MAX_FILE=2*1024*1024;
    public final Path root;
    private final Path rootReal;
    public final String worldId;
    public final long seed;
    public final WorldTime anchor;
    public JsonStockStore(Path saveRoot,WorldTime now) throws IOException {
        Files.createDirectories(saveRoot);
        Path save=saveRoot.toRealPath();
        root=save.resolve(NAMESPACE);
        if(Files.isSymbolicLink(root)) throw new IOException("symbolic store root");
        Files.createDirectories(root);
        if(!root.toRealPath().startsWith(save)) throw new IOException("store escaped save");
        rootReal=root.toRealPath();
        Path world=root.resolve("world.json");
        if(Files.exists(world)) {
            JSONObject data=read(world);header(data,"world");
            worldId=validWorld(data.getString("worldId"));seed=data.getLong("seed");anchor=parseTime(data.getJSONObject("initialTime"));
            if(now.hours()<anchor.hours()) throw new IOException("world clock precedes store anchor");
        } else {
            if(hasFixedZone()) throw new IOException("zone files without world identity; refusing reset");
            worldId=UUID.randomUUID().toString();seed=new java.security.SecureRandom().nextLong();anchor=now;
            atomic(world,base("world").put("worldId",worldId).put("seed",seed).put("initialTime",time(anchor)));
        }
        discardTemps();
    }
    private boolean hasFixedZone() throws IOException {
        Path zones=root.resolve("zones");if(!Files.exists(zones)) return false;
        try(var files=Files.walk(zones)) {return files.anyMatch(path->path.toString().endsWith(".json"));}
    }
    public ZoneStock load(String id) throws IOException {
        Path path=zonePath(id);if(!Files.exists(path)) return null;
        try {
            JSONObject data=read(path);header(data,"zone");
            if(!worldId.equals(data.getString("worldId"))||!id.equals(data.getString("zoneId"))) throw new IOException("zone identity mismatch");
            JSONObject source=data.getJSONObject("source"),rounding=data.getJSONObject("rounding"),stocks=data.getJSONObject("categories");
            var categories=new LinkedHashMap<String,ZoneStock.Category>();
            for(String name:new java.util.TreeSet<>(stocks.keySet())) {
                JSONObject value=stocks.getJSONObject(name);
                if(value.has("reserved")) throw new IOException("reservation in fixed file");
                categories.put(name,new ZoneStock.Category(integer(value,"baseCapacity"),value.getDouble("monthPhase"),integer(value,"capacityAtSnapshot"),integer(value,"available"),integer(value,"inactive"),value.getDouble("regenerationFraction")));
            }
            return new ZoneStock(id,source.getString("biome"),source.getString("map"),source.getString("geometryKey"),source.getDouble("effectiveLandAreaTiles"),source.getDouble("biomeRefillPercent"),rounding.getDouble("totalPhase"),rounding.getDouble("splitPhase"),parseTime(data.getJSONObject("lastUpdate")),categories,integer(data,"snapshotNumber"));
        } catch(JSONException|IllegalArgumentException error) {throw new IOException("invalid fixed zone "+id,error);}
    }
    public void save(ZoneStock live) throws IOException {
        ZoneStock fixed=live.fixedCopy();
        long next=Math.addExact(live.generation,1);
        JSONObject data=encode(fixed).put("snapshotNumber",next);
        atomic(zonePath(live.id),data);
        live.generation=next; // update only after successful replacement
    }
    public void working(ZoneStock live) throws IOException {
        Path dir=safeDirectory(root.resolve("working").resolve(live.id.substring(0,2)));
        JSONObject data=encode(live).put("format",NAMESPACE+".working");
        JSONObject counts=new JSONObject();live.categories.forEach((name,stock)->counts.put(name,stock.reserved));
        data.put("reservedCounts",counts); // aggregates only, never fund identities or a recovery log
        Path file=dir.resolve(live.id+".tmp");
        if(Files.isSymbolicLink(file)) throw new IOException("symbolic working file");
        Files.writeString(file,data.toString(2)+"\n",java.nio.charset.StandardCharsets.UTF_8);
    }
    private JSONObject encode(ZoneStock stock) {
        JSONObject data=base("zone").put("snapshotNumber",stock.generation).put("worldId",worldId).put("zoneId",stock.id)
            .put("source",new JSONObject().put("kind","vanilla").put("map",stock.source).put("biome",stock.biome).put("geometryKey",stock.geometry).put("effectiveLandAreaTiles",stock.area).put("biomeRefillPercent",stock.biomePercent))
            .put("lastUpdate",time(stock.time)).put("rounding",new JSONObject().put("totalPhase",stock.totalPhase).put("splitPhase",stock.splitPhase));
        JSONObject categories=new JSONObject();
        stock.categories.forEach((name,value)->categories.put(name,new JSONObject().put("baseCapacity",value.base).put("monthPhase",value.phase).put("capacityAtSnapshot",value.capacity).put("available",value.available).put("inactive",value.inactive).put("regenerationFraction",value.fraction)));
        return data.put("categories",categories);
    }
    private Path zonePath(String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f]{64}")) throw new IOException("invalid zone key");
        return safeDirectory(root.resolve("zones").resolve(id.substring(0,2))).resolve(id+".json");
    }
    private Path safeDirectory(Path path) throws IOException {
        if(Files.isSymbolicLink(root)||!root.toRealPath().equals(rootReal)) throw new IOException("store root changed");
        Path current=root;
        for(Path part:root.relativize(path)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current)) throw new IOException("symbolic directory in store");
        }
        Files.createDirectories(path);
        if(!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("directory escaped store");
        return path;
    }
    private void discardTemps() throws IOException {
        try(var files=Files.walk(root)) {
            for(Path path:files.toList()) {
                if(Files.isSymbolicLink(path)) throw new IOException("symbolic path in store");
                String name=path.getFileName().toString();
                boolean work=path.startsWith(root.resolve("working"))&&name.matches("[0-9a-f]{64}\\.tmp");
                if(Files.isRegularFile(path)&&(work||name.matches("[0-9a-f]{64}\\.json\\.commit\\.tmp")||name.equals("world.json.commit.tmp"))) Files.delete(path);
            }
        }
    }
    private static void atomic(Path fixed,JSONObject data) throws IOException {
        Path temp=fixed.resolveSibling(fixed.getFileName()+".commit.tmp");
        if(Files.isSymbolicLink(fixed)||Files.isSymbolicLink(temp)) throw new IOException("symbolic state file");
        byte[] bytes=(data.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
        JSONObject reread=read(temp);
        if(!reread.similar(data)) throw new IOException("snapshot reread mismatch");
        for(int attempt=0;;attempt++) {
            try {Files.move(temp,fixed,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return;}
            catch(AccessDeniedException busy) {
                if(attempt>=24) throw busy;
                try {Thread.sleep(2);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
            }
        }
    }
    public static JSONObject read(Path path) throws IOException {
        if(Files.isSymbolicLink(path)||Files.size(path)>MAX_FILE) throw new IOException("unsafe or oversized JSON");
        try {
            String text=Files.readString(path);
            JSONParserConfiguration config=new JSONParserConfiguration().withStrictMode().withOverwriteDuplicateKey(false).withMaxNestingDepth(20);
            JSONTokener tokens=new JSONTokener(text,config);
            JSONObject data=new JSONObject(tokens,config);
            if(tokens.nextClean()!=0) throw new IOException("trailing JSON data");
            return data;
        } catch(JSONException failure) {throw new IOException("invalid JSON "+path.getFileName(),failure);}
    }
    private static JSONObject base(String kind) {return new JSONObject().put("format",NAMESPACE+"."+kind).put("schemaVersion",1);}
    private static void header(JSONObject data,String kind) throws IOException {
        if(!data.getString("format").equals(NAMESPACE+"."+kind)||integer(data,"schemaVersion")!=1) throw new IOException("unsupported state format/schema");
    }
    private static String validWorld(String value) throws IOException {
        try {UUID.fromString(value);return value;}catch(IllegalArgumentException error){throw new IOException("invalid world identity",error);}
    }
    private static long integer(JSONObject data,String key) throws IOException {
        Object value=data.get(key);
        if(!(value instanceof Number number)) throw new IOException("numeric field required: "+key);
        try {long result=new java.math.BigDecimal(number.toString()).longValueExact();if(result<0) throw new IOException("negative count");return result;}
        catch(ArithmeticException error){throw new IOException("invalid integer: "+key,error);}
    }
    private static JSONObject time(WorldTime time) {
        LocalDateTime date=time.calendar();
        return new JSONObject().put("worldAgeHours",time.hours()).put("year",date.getYear()).put("month",date.getMonthValue()).put("day",date.getDayOfMonth()).put("hour",date.getHour()+date.getMinute()/60d+date.getSecond()/3600d+date.getNano()/3_600_000_000_000d);
    }
    private static WorldTime parseTime(JSONObject data) {
        double hour=data.getDouble("hour");
        if(!Double.isFinite(hour)||hour<0||hour>=24) throw new IllegalArgumentException("calendar hour");
        return new WorldTime(data.getDouble("worldAgeHours"),LocalDate.of(data.getInt("year"),data.getInt("month"),data.getInt("day")).atStartOfDay().plusNanos(Math.round(hour*3_600_000_000_000d)));
    }
}
