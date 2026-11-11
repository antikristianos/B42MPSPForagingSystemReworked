package antikristianos.foraging;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One fixed server-selected target per pending measurement; bounded retries never create a new trial. */
public final class LightQueries {
    public static final int PROTOCOL=3,MAX_REACH=6,MAX_SENDS=3;
    public static final long LIFETIME_MS=10_000,RETRY_MS=750;
    public record Request(String id,String session,int player,int x,int y,int z,int targetX,int targetY,int targetZ,long deadline) {}
    private static final class Pending {
        final Request request;int sends;long nextSend;
        Pending(Request request){this.request=request;nextSend=request.deadline-LIFETIME_MS;}
    }
    private final String session=UUID.randomUUID().toString();
    private final Map<Integer,Pending> pending=new HashMap<>();
    public static boolean inRange(int x,int y,int z,int targetX,int targetY,int targetZ){return z==targetZ&&Math.hypot((double)x-targetX,(double)y-targetY)<=MAX_REACH;}
    public Request request(int player,int x,int y,int z,long now) {
        return requestTarget(player,x,y,z,x,y,z,now);
    }
    public Request requestTarget(int player,int x,int y,int z,int targetX,int targetY,int targetZ,long now) {
        if(!inRange(x,y,z,targetX,targetY,targetZ))throw new IllegalArgumentException("light target out of range");
        Pending old=pending.get(player);
        if(old!=null&&old.request.deadline>now)return null;
        Request next=new Request(UUID.randomUUID().toString(),session,player,x,y,z,targetX,targetY,targetZ,now+LIFETIME_MS);
        pending.put(player,new Pending(next));return next;
    }
    public Request peek(int player){Pending value=pending.get(player);return value==null?null:value.request;}
    public Request nextSend(int player,long now){
        Pending value=pending.get(player);
        if(value==null||now>=value.request.deadline||value.sends>=MAX_SENDS||now<value.nextSend)return null;
        value.sends++;value.nextSend=now+RETRY_MS;return value.request;
    }
    public int sendCount(int player){Pending value=pending.get(player);return value==null?0:value.sends;}
    public boolean matches(int player,String id,String responseSession){Request value=peek(player);return value!=null&&value.id.equals(id)&&value.session.equals(responseSession);}
    public String acceptReason(int player,String id,String responseSession,int x,int y,int z,double value,long now) {
        Request request=peek(player);
        if(request==null)return "no_request";
        if(!request.id.equals(id))return "request_mismatch";
        if(!request.session.equals(responseSession))return "session_mismatch";
        if(now>=request.deadline)return "expired";
        if(!Double.isFinite(value)||value<0||value>1)return "invalid_light";
        if(z!=request.targetZ)return "wrong_floor";
        if(!inRange(x,y,z,request.targetX,request.targetY,request.targetZ))return "out_of_range";
        pending.remove(player);return "accepted";
    }
    public boolean accept(int player,String id,String responseSession,int x,int y,int z,double value,long now) {
        return acceptReason(player,id,responseSession,x,y,z,value,now).equals("accepted");
    }
    public void cancel(int player) {pending.remove(player);}
    public int pendingCount() {return pending.size();}
}
