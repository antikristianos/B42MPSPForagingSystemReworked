package antikristianos.foraging;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Measurements, not client-selected attempts or stock. Server positions are captured at request time. */
public final class LightQueries {
    public record Request(String id,String session,int player,int x,int y,int z,long deadline) {}
    private final String session=UUID.randomUUID().toString();
    private final Map<Integer,Request> pending=new HashMap<>();
    public Request request(int player,int x,int y,int z,long now) {
        Request old=pending.get(player);
        if(old!=null&&old.deadline>now&&old.x==x&&old.y==y&&old.z==z) return null;
        Request next=new Request(UUID.randomUUID().toString(),session,player,x,y,z,now+10_000);
        pending.put(player,next);return next;
    }
    public boolean accept(int player,String id,String responseSession,int x,int y,int z,double value,long now) {
        Request request=pending.get(player);
        if(request==null||now>=request.deadline||!Double.isFinite(value)||value<0||value>1||!request.id.equals(id)||!session.equals(responseSession)||request.x!=x||request.y!=y||request.z!=z) return false;
        pending.remove(player);return true;
    }
    public void cancel(int player) {pending.remove(player);}
    public int pendingCount() {return pending.size();}
}
