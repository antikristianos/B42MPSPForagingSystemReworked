package antikristianos.foraging;

import java.util.*;
import se.krka.kahlua.vm.KahluaTable;

/** Neutral, initialized item catalogs only; never filters stock by a player's current conditions. */
public final class ForageDefinitions {
    private ForageDefinitions() {}
    public static final class NotReady extends IllegalStateException {
        NotReady(String context){super("Vanilla item catalog not ready: "+context);}
    }
    private static KahluaTable ready(Object value,String context){if(value instanceof KahluaTable t)return t;throw new NotReady(context);}
    public static Map<String,Set<String>> eligible(KahluaTable system,KahluaTable zones,KahluaTable categories){
        KahluaTable catalog=ready(system.rawget("lootTable"),"lootTable"),definitions=ready(system.rawget("itemDefs"),"itemDefs");
        Map<String,Set<String>> result=new TreeMap<>();var zone=zones.iterator();
        while(zone.advance()){
            String biome=(String)zone.getKey();Set<String> eligible=new HashSet<>();
            KahluaTable entries=ready(ready(catalog.rawget(biome),biome).rawget("categories"),biome+" categories");
            var category=categories.iterator();
            while(category.advance()){
                String name=(String)category.getKey();
                KahluaTable items=ready(ready(entries.rawget(name),biome+"/"+name).rawget("items"),biome+"/"+name+" items");
                var item=items.iterator();
                while(item.advance()){
                    KahluaTable definition=ready(ready(item.getValue(),"item entry").rawget("definition"),"item definition");
                    if(definitions.rawget(item.getKey())!=definition)throw new NotReady("stale item "+item.getKey());
                    Object weight=ready(definition.rawget("zones"),"item zones").rawget(biome);
                    if(weight!=null&&(!(weight instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0))throw new IllegalStateException("invalid neutral item chance: "+biome+"/"+name+"/"+item.getKey());
                    if(weight instanceof Number n&&n.doubleValue()>0&&!name.equals("Tracks"))eligible.add(name);
                }
            }
            result.put(biome,Set.copyOf(eligible));
        }
        // An old, otherwise well-formed table must not classify newly registered items as absent.
        var registered=definitions.iterator();
        while(registered.advance()){
            KahluaTable definition=ready(registered.getValue(),"registered item");
            var biomes=ready(definition.rawget("zones"),"registered item zones").iterator();
            while(biomes.advance()){
                Object biome=biomes.getKey();if(zones.rawget(biome)==null)continue;
                KahluaTable entries=ready(ready(catalog.rawget(biome),"registered biome").rawget("categories"),"registered categories");
                var memberships=ready(definition.rawget("categories"),"item memberships").iterator();
                while(memberships.advance()){
                    Object name=memberships.getValue();if(categories.rawget(name)==null)continue;
                    KahluaTable items=ready(ready(entries.rawget(name),"registered category").rawget("items"),"registered items");
                    if(ready(items.rawget(registered.getKey()),"unbuilt item "+registered.getKey()).rawget("definition")!=definition)throw new NotReady("unbuilt item "+registered.getKey());
                }
            }
        }
        return result;
    }
}
