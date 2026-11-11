package antikristianos.foraging;

import java.util.*;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/** Local inventory mutation commits as one batch; network/UI notifications happen afterwards. */
public final class ItemBatch {
    private ItemBatch() {}
    public static boolean add(ItemContainer target,List<InventoryItem> items){
        return add(target,items,item->target.AddItem(item));
    }
    static boolean add(ItemContainer target,List<InventoryItem> items,java.util.function.Consumer<InventoryItem> insert){
        if(target==null||items.isEmpty()||items.stream().anyMatch(item->item==null||item.getContainer()!=null||item.getWorldItem()!=null))return false;
        try{
            for(InventoryItem item:items){insert.accept(item);if(item.getContainer()!=target||!target.getItems().contains(item))throw new IllegalStateException("native inventory did not retain issued item");}
            return true;
        }catch(RuntimeException failure){
            for(InventoryItem item:items)if(item.getContainer()==target||target.getItems().contains(item)){target.Remove(item);if(item.getContainer()==target||target.getItems().contains(item))throw new IllegalStateException("native inventory rollback failed",failure);}
            return false;
        }
    }
}
