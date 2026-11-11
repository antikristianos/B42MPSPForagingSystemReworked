package antikristianos.foraging;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.weather.ClimateManager;

/** Read-only native target/torch witness. These fields do not replace the measured RGB value. */
public final class LightDiagnostics {
    private LightDiagnostics() {}
    public static String describe(IsoPlayer player,IsoGridSquare square,int index) {
        if(player==null||square==null||index<0||index>=4||index!=player.getPlayerNum())return "diagnostic=invalid_context";
        float rgb=square.getLightLevel(index);
        double packed=Math.max(square.GetRLightLevel(),Math.max(square.GetGLightLevel(),square.GetBLightLevel()))/255d;
        var lamp=player.getActiveLightItem();
        String lampType=lamp==null?"none":lamp.getFullType().replaceAll("[^A-Za-z0-9_.-]","_");
        return "slot="+index+" rgb="+rgb+" packed="+packed+" dark="+square.getDarkMulti(index)+" targetDark="+square.getTargetDarkMulti(index)
            +" torch="+player.getTorchStrength()+" lamp="+lampType+" lampOn="+(lamp!=null&&lamp.isActivated())+" range="+(lamp==null?0:lamp.getLightDistance())
            +" cone="+(lamp!=null&&lamp.isTorchCone())+" facing="+player.getLookAngleRadians()+" outside="+square.isOutside()
            +" daylight="+ClimateManager.getInstance().getDayLightStrength()+" hour="+GameTime.getInstance().getTimeOfDay()
            +" from="+player.getX()+","+player.getY()+","+player.getZ()+" at="+square.getX()+","+square.getY()+","+square.getZ()+torches(square);
    }
    private static String torches(IsoGridSquare square) {
        var sources=new java.util.ArrayList<zombie.characters.IsoGameCharacter.TorchInfo>();
        zombie.iso.LightingJNI.getTorches(sources);
        var text=new StringBuilder(" nativeTorches=").append(sources.size());int shown=0;
        for(var source:sources){
            if(shown==8)break;
            if(!Float.isFinite(source.dist)||source.dist<0||Math.abs(source.z-square.getZ())>1||Math.hypot(source.x-square.getX(),source.y-square.getY())>source.dist+2)continue;
            text.append(" torch").append(shown++).append('=').append(source.id).append(':').append(source.x).append(',').append(source.y).append(',').append(source.z)
                .append(':').append(source.strength).append(':').append(source.dist).append(':').append(source.angleX).append(',').append(source.angleY).append(':').append(source.cone).append(':').append(source.dot);
        }
        return text.toString();
    }
}
