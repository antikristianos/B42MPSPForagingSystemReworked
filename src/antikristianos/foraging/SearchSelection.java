package antikristianos.foraging;

import java.util.*;
import java.util.function.DoubleSupplier;

/** One category draw per layer, then one item draw. No redraw for depleted stock. */
public final class SearchSelection {
    public record Result(String category,String item,String reason,double focusChance){}
    public interface Rules {String item(String category);boolean valid(String item,String category);}
    public static String draw(Map<String,Double> weights,double unit){
        if(!Double.isFinite(unit)||unit<0||unit>=1)throw new IllegalArgumentException("random unit");
        double total=0;for(double value:weights.values()){if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("weights");total+=value;}
        if(total==0)return null;if(!Double.isFinite(total))throw new IllegalArgumentException("weight sum");
        double roll=total*unit,prefix=0;for(var entry:new TreeMap<>(weights).entrySet()){prefix+=entry.getValue();if(entry.getValue()>0&&roll<prefix)return entry.getKey();}throw new IllegalStateException("weighted selection");
    }
    public static Result select(ZoneStock stock,Map<String,Double> weights,String focus,double bonus,DoubleSupplier random,Rules rules){
        var focused=stock.categories.get(focus);boolean available=focused!=null&&focused.available>0;
        Map<String,Double> chances=StockService.focus(weights,focus,bonus,available);
        double focusChance=chances.getOrDefault(focus,0d);
        String category=draw(chances,random.getAsDouble());
        if(category==null)return new Result(null,null,"no_category",focusChance);
        var pool=stock.categories.get(category);
        if(pool==null||pool.available==0)return new Result(category,null,"empty_category",focusChance);
        String item=rules.item(category);
        if(item==null)return new Result(category,null,"no_item",focusChance);
        if(!rules.valid(item,category))return new Result(category,item,"item_rejected",focusChance);
        return new Result(category,item,"match",focusChance);
    }
}
