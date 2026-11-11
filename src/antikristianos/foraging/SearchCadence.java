package antikristianos.foraging;

import java.util.function.IntUnaryOperator;

/** The native force-find movement/time opportunity, without personal icon pools. */
public final class SearchCadence {
    private final IntUnaryOperator random;
    private double foundX,foundY,maxDistance;
    private long since,delay,sequence;
    private int extra;
    private boolean waiting;
    public SearchCadence(double x,double y,int level,long now,IntUnaryOperator random){this.random=random;foundX=x;foundY=y;reset(level,now);}
    private void reset(int level,long now){since=now;delay=30_000L+Math.max(0,Math.min(10,level))*1500L+random.applyAsInt(30_000);extra=random.applyAsInt(20);maxDistance=0;waiting=false;}
    public long opportunity(double x,double y,long now){
        if(waiting||now<since)return 0;
        double distance=Math.hypot(x-foundX,y-foundY);boolean outward=distance>maxDistance;maxDistance=Math.max(maxDistance,distance);
        if(!outward||distance<=10+extra||now-since<delay)return 0;
        waiting=true;return ++sequence;
    }
    public void finish(boolean discovered,double x,double y,int level,long now){if(!waiting)throw new IllegalStateException("no pending opportunity");if(discovered){foundX=x;foundY=y;}reset(level,now);}
    public boolean waiting(){return waiting;}
    public double distance(double x,double y){return Math.hypot(x-foundX,y-foundY);}
}
