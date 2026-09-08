package fr.tofuxia.desktop;

import fr.tofuxia.app.LoadingManager;
import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiBuilder;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.app.DisplayMetrics;
import fr.tofuxia.renderer.BootstrapRenderer;
import io.github.juloass.localization.TextComponent;
import io.github.juloass.localization.Localization;

final class LoadingScreen {
    private final FontAtlas uiFont;
    private final FontAtlas gameTitleFont;
    private final float userScale;
    private final float textScale;
    private final Localization localization;
    private final String windowTitle;
    private final String loadingLogo;
    private final BootstrapRenderer.TextureSize loadingLogoSize;

    LoadingScreen(FontAtlas uiFont, FontAtlas gameTitleFont, float userScale, float textScale,
                  Localization localization, String windowTitle, String loadingLogo,
                  BootstrapRenderer.TextureSize loadingLogoSize) {
        this.uiFont = uiFont;
        this.gameTitleFont = gameTitleFont;
        this.userScale = Math.max(.75f, Math.min(2, userScale));
        this.textScale = Math.max(1, Math.min(1.5f, textScale));
        this.localization = localization;
        this.windowTitle = windowTitle;
        this.loadingLogo = loadingLogo;
        this.loadingLogoSize = loadingLogoSize;
    }

    UiRenderData build(DisplayMetrics display, LoadingManager.Snapshot state) {
        float scaleX = display.framebufferScaleX() * userScale;
        float scaleY = display.framebufferScaleY() * userScale;
        UiBuilder ui = new UiBuilder(uiFont, textScale, scaleX, scaleY);
        float w=Math.max(1,display.framebufferWidth()/scaleX);
        float h=Math.max(1,display.framebufferHeight()/scaleY);
        ui.quad(0,0,w,h,rgba(0,0,0,1));

        float panelW=Math.min(560,w-64),panelX=(w-panelW)*.5f;
        float titleY;
        float globalBarY;
        if (!loadingLogo.isBlank() && loadingLogoSize != null) {
            float logoH=Math.min(220,h*.34f);
            float logoW=logoH*loadingLogoSize.width()/loadingLogoSize.height();
            titleY=Math.max(24,h*.5f-logoH*.75f-52);
            ui.sprite((w-logoW)*.5f,titleY,(w+logoW)*.5f,titleY+logoH,
                    0,0,1,1,loadingLogo,false,rgba(1,1,1,1));
            globalBarY=titleY+logoH+24;
        } else {
            titleY=Math.max(48,h*.5f-gameTitleFont.lineHeight()-52);
            textCentered(ui,gameTitleFont,w,titleY,windowTitle,rgba(1,1,1,1));
            globalBarY=titleY+gameTitleFont.lineHeight()+34;
        }
        float globalBarH=8;
        progressTrack(ui,panelX,globalBarY,panelW,globalBarH,state.progress());
        String overall="Startup  "+state.completedTasks()+" / "+state.totalTasks();
        textCentered(ui,uiFont,w,globalBarY+17,overall,rgba(.68f,.68f,.68f,1));

        String task=truncate(state.task(),72);
        textCentered(ui,uiFont,w,globalBarY+43,task,rgba(1,1,1,1));
        float taskBarY=globalBarY+66,taskBarH=12;
        ui.quad(panelX,taskBarY,panelX+panelW,taskBarY+taskBarH,rgba(.12f,.12f,.12f,1));
        if(state.progress()>=1&&state.error().isBlank()){
            ui.quad(panelX+2,taskBarY+2,panelX+panelW-2,taskBarY+taskBarH-2,rgba(1,1,1,1));
        }else if(state.taskDeterminate()){
            float taskFill=state.taskTotal()<=0?0:Math.clamp(state.taskCompleted()/(float)state.taskTotal(),0,1);
            ui.quad(panelX+2,taskBarY+2,panelX+2+(panelW-4)*taskFill,taskBarY+taskBarH-2,rgba(1,1,1,1));
        }else if(state.error().isBlank()&&state.progress()<1){
            float travel=panelW-4;
            float segment=Math.max(36,travel*.18f);
            float phase=(System.nanoTime()%1_600_000_000L)/1_600_000_000f;
            float wave=phase<.5f?phase*2:(1-phase)*2;
            float x=panelX+2+(travel-segment)*wave;
            ui.quad(x,taskBarY+2,x+segment,taskBarY+taskBarH-2,rgba(.72f,.72f,.72f,1));
        }
        ui.border(panelX,taskBarY,panelW,taskBarH,rgba(.42f,.42f,.42f,1));

        String detail=state.progress()>=1&&state.error().isBlank()?"Ready":
                state.taskDetail().isBlank()?"Working":state.taskDetail();
        if(state.taskDeterminate()){
            detail += "  "+state.taskCompleted()+" / "+state.taskTotal();
            if(!state.taskUnit().isBlank())detail += " "+state.taskUnit();
        }
        textCentered(ui,uiFont,w,taskBarY+30,truncate(detail,82),rgba(.72f,.72f,.72f,1));
        if(!state.error().isBlank()){
            textCentered(ui,uiFont,w,taskBarY+58,localization.resolvePlainText(
                    TextComponent.translatable("screen.engine.loading.failed")),rgba(1,.35f,.35f,1));
            textCentered(ui,uiFont,w,taskBarY+84,truncate(state.error(),92),rgba(1,1,1,1));
        }
        return ui.toRenderData();
    }

    private static void progressTrack(UiBuilder ui,float x,float y,float width,float height,float progress){
        ui.quad(x,y,x+width,y+height,rgba(.12f,.12f,.12f,1));
        float fill=Math.clamp(progress,0,1);
        ui.quad(x+2,y+2,x+2+(width-4)*fill,y+height-2,rgba(.72f,.72f,.72f,1));
        ui.border(x,y,width,height,rgba(.32f,.32f,.32f,1));
    }

    private void textCentered(UiBuilder ui,FontAtlas font,float width,float y,String text,float[] color){
        ui.text(font,(width-font.textWidth(text))*.5f,y,text,color);
    }

    private static String truncate(String value,int max){
        return value.length()<=max?value:value.substring(0,max-3)+"...";
    }
    private static float[] rgba(float r,float g,float b,float a){return new float[]{r,g,b,a};}
}
