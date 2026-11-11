package antikristianos.foraging;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.IsoGridSquare;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;
import zombie.network.ServerLOS;

/** Player-slot measurements at one requested square. No item or find decision is delegated. */
public record SightSample(double light,double dark,int seen) {
    public SightSample {
        if(!Double.isFinite(light)||light<0||light>1||!Double.isFinite(dark)||dark<0||dark>Float.MAX_VALUE||seen<0||seen>31)throw new IllegalArgumentException("invalid sight sample");
    }
    public static SightSample decode(KahluaTable data){
        Object light=data.rawget("light"),dark=data.rawget("dark"),seen=data.rawget("seen");
        if(!(light instanceof Number l)||!(dark instanceof Number d)||!(seen instanceof Number s)||!Double.isFinite(s.doubleValue())||s.doubleValue()<0||s.doubleValue()>31||s.doubleValue()!=Math.rint(s.doubleValue()))throw new IllegalArgumentException("malformed sight sample");
        return new SightSample(l.doubleValue(),d.doubleValue(),s.intValue());
    }
    public KahluaTable data(){KahluaTable data=LuaManager.platform.newTable();data.rawset("light",light);data.rawset("dark",dark);data.rawset("seen",(double)seen);return data;}
    static IsoGridSquare[] squares(IsoGridSquare square){return new IsoGridSquare[]{square,square.getN(),square.getS(),square.getE(),square.getW()};}
    public static SightSample local(IsoPlayer player,IsoGridSquare square,int slot){
        if(GameServer.server||player==null||square==null||slot<0||slot>=4||player.getPlayerNum()!=slot)throw new IllegalArgumentException("invalid local sight context");
        int seen=0,index=0;
        for(var adjacent:squares(square)){if(adjacent!=null&&adjacent.isCanSee(slot))seen|=1<<index;index++;}
        return new SightSample(square.getLightLevel(slot),square.getDarkMulti(slot),seen);
    }
    /** Client direction cannot bypass an authoritative wall, missing square or geometric LOS. */
    public SightSample bounded(IsoPlayer player,IsoGridSquare square){
        int permitted=0,index=0;
        for(var adjacent:squares(square)){
            if(adjacent!=null&&player.getCurrentSquare()!=null&&adjacent.getZ()==player.getZ()&&!adjacent.isBlockedTo(player.getCurrentSquare())
                    &&(!GameServer.server||ServerLOS.instance!=null&&ServerLOS.instance.isCouldSee(player,adjacent)))permitted|=1<<index;
            index++;
        }
        return new SightSample(light,dark,seen&permitted);
    }
}
