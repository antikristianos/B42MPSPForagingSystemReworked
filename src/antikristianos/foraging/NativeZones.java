package antikristianos.foraging;

import java.util.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.IsoMetaGrid;
import zombie.iso.MapFiles;
import zombie.iso.worldgen.maps.BiomeMap;
import zombie.iso.zones.Zone;

/** Read-only metagrid/raster adapter. Does not register zones or request native ForageZone stock. */
public final class NativeZones implements ZoneCatalog.Source {
    private final IsoMetaGrid grid;
    private final BiomeMap raster;
    private final Map<String,ZoneCatalog.Definition> definitions;
    private final IdentityHashMap<Zone,ZoneCatalog.Shape> shapes=new IdentityHashMap<>();
    public NativeZones(IsoMetaGrid grid,BiomeMap raster,Map<String,ZoneCatalog.Definition> definitions){this.grid=Objects.requireNonNull(grid);this.raster=Objects.requireNonNull(raster);this.definitions=Map.copyOf(definitions);}
    public static Map<String,ZoneCatalog.Definition> definitions() {
        KahluaTable system=table(LuaManager.env.rawget("forageSystem"));
        KahluaTable zones=table(system.rawget("zoneDefs")),categories=table(system.rawget("catDefs"));
        if(!zones.iterator().advance())throw new ForageDefinitions.NotReady("zone definitions");
        if(!categories.iterator().advance())throw new ForageDefinitions.NotReady("category definitions");
        Map<String,Set<String>> eligible=ForageDefinitions.eligible(system,zones,categories);
        KahluaTable settings=table(system.rawget("abundanceSettings")),sandbox=table(LuaManager.env.rawget("SandboxVars"));
        Map<String,ZoneCatalog.Definition> result=new TreeMap<>();
        var iterator=zones.iterator();
        while(iterator.advance()) {
            String biome=(String)iterator.getKey();KahluaTable zone=table(iterator.getValue());
            Map<String,Double> weights=new TreeMap<>();var cat=categories.iterator();
            while(cat.advance()) {
                String name=(String)cat.getKey();Object value=table(table(cat.getValue()).rawget("zones")).rawget(biome);
                if(value instanceof Number weight&&weight.doubleValue()>0&&eligible.get(biome).contains(name))weights.put(name,weight.doubleValue());
            }
            Object setting=zone.rawget("abundanceSetting");String settingName=setting instanceof String?(String)setting:"NatureAbundance";
            Object settingIndex=sandbox.rawget(settingName);
            if(!(settingIndex instanceof Number))throw new IllegalStateException("missing abundance setting "+settingName);
            double bonus=number(table(settings.rawget(settingName)).rawget(((Number)settingIndex).doubleValue()));
            result.put(biome,new ZoneCatalog.Definition(number(zone.rawget("densityMin")),number(zone.rawget("densityMax")),1+bonus/100,number(zone.rawget("refillPercent")),weights));
        }
        if(result.isEmpty())throw new IllegalStateException("Vanilla foraging definitions not initialized");
        return result;
    }
    private static KahluaTable table(Object value){if(value instanceof KahluaTable t)return t;throw new IllegalStateException("Vanilla foraging table missing");}
    private static double number(Object value){if(value instanceof Number n&&Double.isFinite(n.doubleValue()))return n.doubleValue();throw new IllegalStateException("invalid Vanilla numeric definition");}
    public ZoneCatalog.Definition definition(String biome){return Objects.requireNonNull(definitions.get(biome),"unknown forage biome "+biome);}
    public List<ZoneCatalog.Shape> at(int x,int y) {
        List<ZoneCatalog.Shape> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        var matches=grid.getZonesAt(x,y,0);
        for(int index=matches.size()-1;index>=0;index--) {
            Zone zone=matches.get(index);
            if(!definitions.containsKey(zone.getType()))continue;
            ZoneCatalog.Shape shape=shape(zone);
            if(seen.add(shape.key()))result.add(shape);
        }
        if(result.isEmpty()) {
            String biome=biomeAt(x,y);
            if(biome!=null&&definitions.containsKey(biome))result.add(residual(x,y,biome));
        }
        return result;
    }
    public static String geometry(Zone zone) {
        String kind=zone.isPolygon()?"polygon":zone.isPolyline()?"polyline":zone.isPoint()?"point":"rectangle";
        StringBuilder value=new StringBuilder(kind).append(':').append(zone.x).append(',').append(zone.y).append(',').append(zone.z).append(',').append(zone.w).append(',').append(zone.h).append(':').append(zone.polylineWidth);
        // Canonical ring rotation/direction: equivalent polygon registrations share one pool.
        int[] points=zone.points.toArray();
        if(kind.equals("polygon")) {
            int n=points.length/2;if(n>1&&points[0]==points[points.length-2]&&points[1]==points[points.length-1])n--;
            String forward=ring(points,n,false),reverse=ring(points,n,true);
            value.append(':').append(forward.compareTo(reverse)<=0?forward:reverse);
        }else {
            StringBuilder forward=new StringBuilder(),reverse=new StringBuilder();
            for(int i=0;i<points.length;i+=2){forward.append(points[i]).append(',').append(points[i+1]).append(';');int r=points.length-2-i;reverse.append(points[r]).append(',').append(points[r+1]).append(';');}
            value.append(':').append(forward.toString().compareTo(reverse.toString())<=0?forward:reverse);
        }
        return value.toString();
    }
    private static String ring(int[] points,int count,boolean reverse) {
        if(count==0)return "";
        String[] tokens=new String[count];for(int i=0;i<count;i++){int p=(reverse?count-1-i:i)*2;tokens[i]=points[p]+","+points[p+1]+";";}
        // Booth's minimal rotation: linear work even for detailed map-mod polygons.
        int left=0,right=1,matched=0;
        while(left<count&&right<count&&matched<count){int compare=tokens[(left+matched)%count].compareTo(tokens[(right+matched)%count]);if(compare==0){matched++;continue;}if(compare>0){left+=matched+1;if(left==right)left++;}else{right+=matched+1;if(left==right)right++;}matched=0;}
        int start=Math.min(left,right);StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(tokens[(start+i)%count]);return result.toString();
    }
    private ZoneCatalog.Shape shape(Zone zone) {
        return shapes.computeIfAbsent(zone,nativeZone->{
            var bounds=new ZoneCatalog.Bounds(nativeZone.x,nativeZone.y,nativeZone.w,nativeZone.h);
            return new ZoneCatalog.Shape(nativeZone.getType(),mapSource(bounds),geometry(nativeZone),bounds,(x,y)->nativeZone.contains(x,y,0),false);
        });
    }
    private ZoneCatalog.Shape residual(int x,int y,String biome) {
        int cellX=Math.floorDiv(x,256),cellY=Math.floorDiv(y,256);
        var bounds=new ZoneCatalog.Bounds(cellX*256,cellY*256,256,256);
        int[] pixels=raster.getCell(cellX,cellY,BiomeMap.Type.ZONE);
        if(pixels==null)throw new IllegalStateException("missing biome raster for residual");
        BitSet mask=new BitSet(65536);
        for(int i=0;i<65536;i++)if(biome.equals(raster.getZoneName(pixels[i])))mask.set(i);
        String geometry="biome-mask-v1:"+cellX+","+cellY+":"+ZoneStock.hash(Arrays.toString(mask.toLongArray()));
        return new ZoneCatalog.Shape(biome,mapSource(bounds),geometry,bounds,(px,py)->bounds.contains(px,py)&&mask.get(px-bounds.x()+(py-bounds.y())*256),true);
    }
    private String mapSource(ZoneCatalog.Bounds bounds) {
        // Source is the effective tile-map provider for each original geometry cell, not an absolute install path.
        StringBuilder key=new StringBuilder("tile-maps-v1:");
        for(int cy=Math.floorDiv(bounds.y(),256);cy<=Math.floorDiv(bounds.y()+bounds.height()-1,256);cy++)for(int cx=Math.floorDiv(bounds.x(),256);cx<=Math.floorDiv(bounds.x()+bounds.width()-1,256);cx++) {
            var cell=grid.getCellData(cx,cy);
            String name=cell!=null&&cell.info!=null&&cell.info.mapFiles!=null?cell.info.mapFiles.mapDirectoryName:null;
            if(name==null) {
                // Same provider selection as loaded lot headers; no dependence on which chunks a player visited.
                for(MapFiles map:MapFiles.getCurrentMapFiles())if(map.getLotHeader(cx,cy)!=null){name=map.mapDirectoryName;break;}
            }
            if(name==null)throw new IllegalStateException("map provenance unavailable at cell "+cx+","+cy);
            key.append(cx).append(',').append(cy).append('=').append(name.length()).append(':').append(name).append(';');
        }
        return key.toString();
    }
    private String biomeAt(int x,int y) {
        int[] pixels=raster.getCell(Math.floorDiv(x,256),Math.floorDiv(y,256),BiomeMap.Type.ZONE);
        return pixels==null?null:raster.getZoneName(pixels[Math.floorMod(x,256)+Math.floorMod(y,256)*256]);
    }
    @Override public ZoneCatalog.View snapshot(ZoneCatalog.Shape target) {
        var bounds=target.bounds();
        Map<StockService.Chunk,List<ZoneCatalog.Shape>> chunks=new LinkedHashMap<>();Map<Long,int[]> cells=new HashMap<>();StringBuilder signature=new StringBuilder(mapSource(bounds));
        for(int cy=Math.floorDiv(bounds.y(),256);cy<=Math.floorDiv(bounds.y()+bounds.height()-1,256);cy++)for(int cx=Math.floorDiv(bounds.x(),256);cx<=Math.floorDiv(bounds.x()+bounds.width()-1,256);cx++) {
            if(grid.getCellData(cx,cy)==null)throw new IllegalStateException("native metacell unavailable at "+cx+","+cy);
            int[] pixels=raster.getCell(cx,cy,BiomeMap.Type.ZONE);int[] copy=pixels==null?null:pixels.clone();
            cells.put(cellKey(cx,cy),copy);signature.append("|r:").append(cx).append(',').append(cy).append(':').append(rasterDigest(copy));
        }
        for(int cy=Math.floorDiv(bounds.y(),8);cy<=Math.floorDiv(bounds.y()+bounds.height()-1,8);cy++)for(int cx=Math.floorDiv(bounds.x(),8);cx<=Math.floorDiv(bounds.x()+bounds.width()-1,8);cx++) {
            var chunk=grid.getChunkData(cx,cy);if(chunk==null)throw new IllegalStateException("native metachunk unavailable");
            List<ZoneCatalog.Shape> ordered=new ArrayList<>();
            for(int i=0;i<chunk.getZonesSize();i++){Zone zone=chunk.getZone(i);if(definitions.containsKey(zone.getType()))ordered.add(shape(zone));}
            chunks.put(new StockService.Chunk(cx,cy),List.copyOf(ordered));signature.append("|c:").append(cx).append(',').append(cy);
            for(var zone:ordered)signature.append('|').append(ZoneStock.hash(zone.key()));
        }
        String fingerprint=ZoneStock.hash(signature.toString());
        return new ZoneCatalog.View(){
            int chunkX=Integer.MIN_VALUE,chunkY=Integer.MIN_VALUE,cellX=Integer.MIN_VALUE,cellY=Integer.MIN_VALUE;
            List<ZoneCatalog.Shape> zones;int[] pixels;
            public String highest(int x,int y){int cx=Math.floorDiv(x,8),cy=Math.floorDiv(y,8);if(cx!=chunkX||cy!=chunkY){chunkX=cx;chunkY=cy;zones=chunks.get(new StockService.Chunk(cx,cy));}if(zones==null)return null;for(int i=zones.size()-1;i>=0;i--){var zone=zones.get(i);if(zone.contains().test(x,y))return zone.key();}return null;}
            public String biome(int x,int y){int cx=Math.floorDiv(x,256),cy=Math.floorDiv(y,256);if(cx!=cellX||cy!=cellY){cellX=cx;cellY=cy;pixels=cells.get(cellKey(cx,cy));}return pixels==null?null:raster.getZoneName(pixels[Math.floorMod(x,256)+Math.floorMod(y,256)*256]);}
            public String fingerprint(){return fingerprint;}
        };
    }
    private static long cellKey(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
    private static String rasterDigest(int[] samples){
        if(samples==null)return "missing";
        var bytes=java.nio.ByteBuffer.allocate(samples.length*4);bytes.asIntBuffer().put(samples);
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
