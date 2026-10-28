package antikristianos.foraging;

import java.util.Map;
import java.util.Set;

/** Initial user-accepted profiles; each category still owns an independent stock. */
public final class PoolProfiles {
    private PoolProfiles() {}
    private static final Set<String> HUMAN=Set.of("Artifacts","CraftingMaterials","ForestRarities","Trash","Junk","JunkFood","Clothing","JunkWeapons","Ammunition","Medical","ForestGoods");
    private static final Map<String,double[]> MONTHS=Map.ofEntries(
        Map.entry("Berries",new double[]{0,0,.25,.5,.75,1,1.5,1.5,1,.5,.25,0}),
        Map.entry("Fruits",new double[]{0,0,0,.25,.5,.75,1,1.25,1.5,1,.25,0}),
        Map.entry("Vegetables",new double[]{.15,.15,.5,.75,1,1.25,1.25,1.25,1,.75,.35,.15}),
        Map.entry("WildHerbs",new double[]{.25,.25,.5,.75,1,1,1,1,1,.75,.5,.25}),
        Map.entry("Mushrooms",new double[]{.1,.1,.5,1.25,1,.75,.75,1,1.5,1.5,.5,.1}),
        Map.entry("Insects",new double[]{.1,.1,.25,.5,.75,1,1.25,1.25,1,.5,.25,.1}),
        Map.entry("Firewood",new double[]{1,1,1,1,1,1,1,1,1.25,1.5,1.25,1}));
    public static double monthFactor(String category,int month) {
        if(month<1||month>12) throw new IllegalArgumentException("month must be 1..12");
        String profile=switch(category) {
            case "Crops" -> "Fruits";
            case "WildPlants" -> "Vegetables";
            case "MedicinalPlants" -> "WildHerbs";
            case "FishBait" -> "Insects";
            default -> category;
        };
        double[] values=MONTHS.get(profile);
        return values==null?1:values[month-1];
    }
    public static double dailyFraction(String category,double biomePercent) {
        if(!Double.isFinite(biomePercent)||biomePercent<0) throw new IllegalArgumentException("biome regeneration");
        if(HUMAN.contains(category)) return .001;
        double rate=switch(category) {
            case "Insects","FishBait" -> 2;
            case "Mushrooms" -> 1.5;
            case "Berries","Fruits","Firewood" -> .5;
            case "Bones" -> .1;
            case "Stones" -> .05;
            default -> 1;
        };
        return biomePercent/100*rate;
    }
    public static long capacity(String category,long base,double phase,int month) {
        return (long)Math.floor(base*monthFactor(category,month)+phase);
    }
}
