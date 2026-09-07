package fr.tofuxia.ui;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiBuilder;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.render.UiSurfaceData;

import java.nio.file.Path;
import java.util.EnumSet;

/** Deterministic geometry, state, scale, clipping, and batching checks for surfaces. */
public final class UiSurfaceSelfTest {
    public static void main(String[] args) throws Exception {
        UiTheme theme=new UiTheme();
        require(close(theme.charcoal,UiTheme.hex("20282B")),"canonical charcoal");
        require(close(theme.accent,UiTheme.hex("FF9C3A")),"canonical orange accent");
        near(.01444384f,theme.charcoal[0],.00001f,"sRGB charcoal red decodes to linear");
        near(.02121901f,theme.charcoal[1],.00001f,"sRGB charcoal green decodes to linear");
        near(.02415763f,theme.charcoal[2],.00001f,"sRGB charcoal blue decodes to linear");
        near(1f,theme.orange[0],.00001f,"sRGB orange red decodes to linear");
        near(.33245154f,theme.orange[1],.00001f,"sRGB orange green decodes to linear");
        near(.04231141f,theme.orange[2],.00001f,"sRGB orange blue decodes to linear");
        require(toSrgbByte(theme.charcoal[0])==0x20&&toSrgbByte(theme.charcoal[1])==0x28
                &&toSrgbByte(theme.charcoal[2])==0x2B,"sRGB swapchain recovers #20282B");
        require(toSrgbByte(theme.orange[0])==0xFF&&toSrgbByte(theme.orange[1])==0x9C
                &&toSrgbByte(theme.orange[2])==0x3A,"sRGB swapchain recovers #FF9C3A");
        require(UiSurfaceData.Shape.RECT.shaderId()==0&&UiSurfaceData.Shape.ROUNDED_RECT.shaderId()==1
                &&UiSurfaceData.Shape.TOP_CENTER_EXTENSION_PANEL.shaderId()==2,"stable shape identifiers");
        require(UiSurfaceStyle.clampRadius(99,10,6)==3,"radius clamps to half the smaller dimension");

        UiSurfaceStyle base=UiSurfaceStyle.button(theme);
        require(base.borderWidth()==1,"default logical one-pixel border");
        require(base.resolve(EnumSet.of(UiVisualState.FOCUSED),UiSurfaceVariant.NORMAL,theme).borderWidth()>=1.5f,"focused border");
        require(base.resolve(EnumSet.of(UiVisualState.SELECTED),UiSurfaceVariant.NORMAL,theme).borderWidth()>=1.5f,"selected border");
        UiSurfaceStyle disabled=base.resolve(EnumSet.of(UiVisualState.DISABLED),UiSurfaceVariant.NORMAL,theme);
        require(disabled.primary()[3]<base.primary()[3],"disabled opacity");
        UiSurfaceStyle danger=base.resolve(EnumSet.noneOf(UiVisualState.class),UiSurfaceVariant.DANGER,theme);
        require(!danger.hasExplicitBorder()&&close(danger.border(),UiTheme.deriveBorder(danger.primary())),
                "danger semantic variant derives border from its resolved fill");
        derivedBorders(theme);
        UiSurfaceStyle canonicalHover=base.toBuilder().primary(theme.warmGold).secondary(theme.warmGold).build();
        UiSurface exactStates=new UiSurface("exact-states",base).stateStyle(UiVisualState.HOVERED,canonicalHover);
        require(close(exactStates.resolveStyle(EnumSet.of(UiVisualState.HOVERED),theme).primary(),theme.warmGold),
                "explicit state style preserves exact palette swatch");

        require(UiPageIndicator.totalWidth(4,6,5)==39,"page indicator width");
        require(UiPageIndicator.positions(4,6,5).equals(java.util.List.of(0f,11f,22f,33f)),"page indicator positions");

        try(FontAtlas font=new FontAtlas(Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"),15)){
            clippingAndScale(font,base,theme);
            extensionBounds(font,base,theme);
            stressBatch(font,base,theme);
            displayScales(font);
        }
        System.out.println("[engine-ui] analytic surface checks passed");
    }

    private static void derivedBorders(UiTheme theme){
        UiSurfaceStyle dark=UiSurfaceStyle.builder().primary(theme.charcoal).build();
        require(!dark.hasExplicitBorder(),"border color is optional");
        require(close(dark.border(),mixPreservingAlpha(theme.charcoal,theme.ivory,.15f)),
                "dark neutral fill gets a subtle ivory edge");

        UiSurfaceStyle saturated=UiSurfaceStyle.builder().primary(theme.orange).build();
        require(close(saturated.border(),mixPreservingAlpha(theme.orange,theme.ivory,.20f)),
                "saturated fill gets a stronger same-material edge");

        UiSurfaceStyle light=UiSurfaceStyle.builder().primary(theme.ivory).build();
        require(close(light.border(),mixPreservingAlpha(theme.ivory,theme.charcoal,.18f)),
                "very light fill gets a darker same-material edge");

        float[] translucent=UiTheme.alpha(theme.charcoal,.57f);
        UiSurfaceStyle derivedAlpha=UiSurfaceStyle.builder().primary(translucent).build();
        near(.57f,derivedAlpha.border()[3],.00001f,"derived border preserves fill alpha");

        UiSurfaceStyle explicit=UiSurfaceStyle.builder().primary(theme.orange).border(theme.vividGreen).build();
        UiSurfaceStyle explicitResolved=explicit.resolve(EnumSet.of(UiVisualState.HOVERED,UiVisualState.FOCUSED,
                UiVisualState.SELECTED,UiVisualState.DISABLED),UiSurfaceVariant.DANGER,theme);
        require(explicit.hasExplicitBorder()&&close(explicitResolved.border(),theme.vividGreen),
                "explicit border override remains unchanged through state resolution");
    }

    private static float[] mixPreservingAlpha(float[] fill,float[] target,float amount){
        float[] result=UiTheme.mix(fill,target,amount);result[3]=fill[3];return result;
    }

    private static void clippingAndScale(FontAtlas font,UiSurfaceStyle style,UiTheme theme){
        UiBuilder builder=new UiBuilder(font,1,1.25f,1.5f);
        builder.pushClip(20,8,40,50);
        builder.surface(10,5,80,30,data(style.toBuilder().shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build()));
        builder.popClip(); UiRenderData render=builder.toRenderData();
        require(render.logicalScaleX()==1.25f&&render.logicalScaleY()==1.5f,"logical-to-framebuffer scale retained");
        require(render.surfaceCount()==1&&render.clippedBatchCount()==1,"surface and clipped batch counters");
        for(int i=0;i<render.vertices().length;i+=UiRenderData.FLOATS_PER_VERTEX){
            float framebufferX=render.vertices()[i], localX=render.vertices()[i+8];
            near(framebufferX/1.25f-10,localX,.002f,"CPU clipping preserves surface-local interpolation");
            near(1,render.vertices()[i+22],.001f,"fractional scale does not alter logical border width");
            require(render.vertices()[i+47]==1,"surface vertex flag");
        }
    }

    private static void extensionBounds(FontAtlas font,UiSurfaceStyle style,UiTheme theme){
        UiSurfaceStyle composite=style.toBuilder().shape(UiSurfaceShape.TOP_CENTER_EXTENSION_PANEL)
                .extension(24,20,3,5,4).cornerRadius(50).shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build();
        UiBuilder builder=new UiBuilder(font);builder.surface(30,50,40,70,data(composite));UiRenderData render=builder.toRenderData();
        float minLocalY=Float.POSITIVE_INFINITY;
        for(int i=0;i<render.vertices().length;i+=UiRenderData.FLOATS_PER_VERTEX){
            minLocalY=Math.min(minLocalY,render.vertices()[i+9]);
            require(render.vertices()[i+20]==2,"composite shape flag");
            near(24,render.vertices()[i+40],.001f,"extension width transport");
            near(5,render.vertices()[i+43],.001f,"extension overlap transport");
        }
        near(-15,minLocalY,.001f,"extension conservative top bound");
        require(render.compositeSurfaceCount()==1,"composite surface counter");
    }

    private static void stressBatch(FontAtlas font,UiSurfaceStyle style,UiTheme theme){
        UiContext ui=new UiContext(font);ui.begin(UiInput.mouseOnly(-1,-1,false,1/60f),new UiViewport(1001,701,1,1,1.25f,UiInsets.all(0)));
        UiGroup clip=new UiGroup("stress");clip.layout().position(7,9).size(500,300).clip(true).flow(UiLayout.Flow.NONE);
        UiSurfaceStyle compact=style.toBuilder().shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).cornerRadius(2).build();
        for(int i=0;i<500;i++){UiSurface surface=new UiSurface("stress/"+i,compact);surface.layout().position((i%25)*19,(i/25)*13).size(i==0?4:16,i==1?80:10).absolute(true);clip.child(surface);}
        ui.submit(clip);UiRenderData render=ui.end();
        require(render.surfaceCount()==500,"500-surface stress count");
        require(render.batches().size()==1,"style changes do not create per-surface draws");
        require(render.clippedBatchCount()==1,"stress clip remains one clipped batch");
    }

    private static void displayScales(FontAtlas font){
        for(float scale:new float[]{1,1.25f,1.5f,2}){
            UiViewport viewport=new UiViewport(1919,1079,1,1,scale,UiInsets.all(0));
            UiContext ui=new UiContext(font);ui.begin(UiInput.mouseOnly(-1,-1,false,1/60f),viewport);
            UiSurface surface=new UiSurface("scale",UiSurfaceStyle.panel(ui.theme()));surface.layout().position(3,5).size(9,31);ui.submit(surface);
            UiRenderData render=ui.end(); near(scale,render.logicalScaleX(),.001f,"root scale applied once");
            for(int i=0;i<render.vertices().length;i+=UiRenderData.FLOATS_PER_VERTEX)near(1,render.vertices()[i+22],.001f,"one logical pixel at scale "+scale);
        }
    }

    private static UiSurfaceData data(UiSurfaceStyle s){return new UiSurfaceData(UiSurfaceData.Shape.valueOf(s.shape().name()),s.primary(),s.secondary(),s.border(),s.borderWidth(),s.cornerRadius(),s.gradientX(),s.gradientY(),s.gradientStrength(),UiSurfaceData.NoiseMode.valueOf(s.noiseMode().name()),s.noiseStrength(),UiSurfaceData.NoiseAnchor.valueOf(s.noiseAnchor().name()),s.edgeDarkening(),s.shadow(),s.shadowOffsetX(),s.shadowOffsetY(),s.shadowExpansion(),s.shadowSoftness(),s.extensionWidth(),s.extensionHeight(),s.extensionOffset(),s.extensionOverlap(),s.extensionRadius(),UiSurfaceData.DebugMode.valueOf(s.debugMode().name()));}
    private static boolean close(float[] a,float[] b){if(a.length<4||b.length<4)return false;for(int i=0;i<4;i++)if(Math.abs(a[i]-b[i])>.002f)return false;return true;}
    private static int toSrgbByte(float linear){float value=linear<=.0031308f?linear*12.92f:1.055f*(float)Math.pow(linear,1/2.4f)-.055f;return Math.round(Math.max(0,Math.min(1,value))*255);}
    private static void near(float expected,float actual,float epsilon,String message){if(Math.abs(expected-actual)>epsilon)throw new AssertionError(message+": expected "+expected+" got "+actual);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
