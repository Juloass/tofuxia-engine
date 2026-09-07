package fr.tofuxia.ui;

import java.util.ArrayList;
import java.util.List;

/** Declarative page dots suitable for a hotbar extension. */
public final class UiPageIndicator extends UiGroup {
    private final int pageCount;
    private final int activePage;
    private final float dotSize;
    private final float spacing;

    public UiPageIndicator(String id,int pageCount,int activePage,float dotSize,float spacing,
                           float[] inactiveColor,float[] activeColor) {
        super(id);
        this.pageCount=Math.max(0,pageCount); this.activePage=Math.max(0,Math.min(this.pageCount-1,activePage));
        this.dotSize=Math.max(1,dotSize); this.spacing=Math.max(0,spacing);
        layout().size(totalWidth(this.pageCount,this.dotSize,this.spacing),this.dotSize).flow(UiLayout.Flow.NONE);
        List<Float> positions=positions(this.pageCount,this.dotSize,this.spacing);
        for(int i=0;i<this.pageCount;i++){
            float[] color=i==this.activePage?activeColor:inactiveColor;
            UiSurface dot=new UiSurface(id+"/dot-"+i,UiSurfaceStyle.builder().primary(color).secondary(color)
                    .border(UiTheme.alpha(color,0)).borderWidth(0).cornerRadius(this.dotSize*.5f).shape(UiSurfaceShape.ROUNDED_RECT)
                    .gradient(0,1,0).noise(UiSurfaceNoiseMode.NONE,0,UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                    .edgeDarkening(0).shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build());
            dot.layout().position(positions.get(i),0).size(this.dotSize,this.dotSize).absolute(true);
            child(dot);
        }
    }

    public int pageCount(){return pageCount;} public int activePage(){return activePage;}
    public float dotSize(){return dotSize;} public float spacing(){return spacing;}
    public static float totalWidth(int count,float size,float spacing){return count<=0?0:count*size+(count-1)*spacing;}
    public static List<Float> positions(int count,float size,float spacing){List<Float> out=new ArrayList<>();for(int i=0;i<Math.max(0,count);i++)out.add(i*(size+spacing));return List.copyOf(out);}
}
