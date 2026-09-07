package fr.tofuxia.ui;

import io.github.juloass.uianimation.UiTransform;

/** Developer-facing deterministic gallery for analytic surface validation. */
public final class UiStyleLab {
    public static final int STRESS_SURFACES=500;
    private UiStyleLab(){}

    public static UiElement build(UiContext ui,float timeSeconds){
        float x=ui.sx(18), y=ui.sx(58), w=Math.min(ui.sx(830),ui.width()-ui.sx(36)), h=Math.min(ui.sx(620),ui.height()-ui.sx(80));
        UiGroup root=new UiGroup("surface-style-lab"); root.layout().position(x,y).size(w,h).flow(UiLayout.Flow.NONE);
        UiSurface backdrop=new UiSurface("surface-style-lab/backdrop",UiSurfaceStyle.panel(ui.theme()).toBuilder().cornerRadius(10).build());
        backdrop.layout().size(w,h).absolute(true); root.child(backdrop);
        root.child(label("surface-style-lab/title","SURFACE STYLE LAB",ui.theme().ivory,12,8));

        float cardW=ui.sx(120), cardH=ui.sx(46), rowY=ui.sx(34), gap=ui.sx(10);
        UiSurfaceStyle base=UiSurfaceStyle.panel(ui.theme()).toBuilder().shadow(UiTheme.rgba(0,0,0,.42f),0,2,1,3).build();
        root.child(card("surface-style-lab/flat",12,rowY,cardW,cardH,base.toBuilder().gradient(0,1,0).noise(UiSurfaceNoiseMode.NONE,0,UiSurfaceNoiseAnchor.ELEMENT_LOCAL).edgeDarkening(0).build()));
        root.child(card("surface-style-lab/gradient",12+cardW+gap,rowY,cardW,cardH,base.toBuilder().noise(UiSurfaceNoiseMode.NONE,0,UiSurfaceNoiseAnchor.ELEMENT_LOCAL).edgeDarkening(0).build()));
        root.child(card("surface-style-lab/noise",12+(cardW+gap)*2,rowY,cardW,cardH,base.toBuilder().gradient(0,1,0).edgeDarkening(0).build()));
        root.child(card("surface-style-lab/combined",12+(cardW+gap)*3,rowY,cardW,cardH,base));

        float borderY=rowY+cardH+ui.sx(12);
        float[] widths={.5f,1,1.5f,2};
        for(int i=0;i<widths.length;i++)root.child(card("surface-style-lab/border-"+widths[i],12+i*ui.sx(84),borderY,ui.sx(72),ui.sx(32),base.toBuilder().borderWidth(widths[i]).build()));
        float[] scales={1,1.25f,1.5f,2};
        for(int i=0;i<scales.length;i++)root.child(card("surface-style-lab/scale-"+scales[i],ui.sx(360)+i*ui.sx(78),borderY,ui.sx(54)*scales[i],ui.sx(22)*scales[i],base.toBuilder().shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build()));

        float buttonY=borderY+ui.sx(52); UiVisualState[] states={null,UiVisualState.HOVERED,UiVisualState.PRESSED,UiVisualState.FOCUSED,UiVisualState.SELECTED,UiVisualState.DISABLED};
        for(int i=0;i<states.length;i++){
            UiSurface button=UiWidgets.button(ui,"surface-style-lab/button-"+i,i==0?"normal":states[i].name().toLowerCase(),ui.sx(92),ui.sx(30),()->{});
            if(states[i]!=null)button.presentation(states[i]);
            if(states[i]==UiVisualState.DISABLED)button.enabled(false);
            button.layout().position(12+i*ui.sx(102),buttonY).absolute(true); root.child(button);
        }
        UiSurface danger=UiWidgets.dangerButton(ui,"surface-style-lab/danger","danger",ui.sx(92),ui.sx(30),()->{});
        danger.layout().position(ui.sx(624),buttonY).absolute(true);root.child(danger);

        float shapeY=buttonY+ui.sx(48);
        root.child(card("surface-style-lab/rect",12,shapeY,ui.sx(110),ui.sx(45),base.toBuilder().shape(UiSurfaceShape.RECT).cornerRadius(0).build()));
        root.child(card("surface-style-lab/rounded",ui.sx(136),shapeY,ui.sx(110),ui.sx(45),base.toBuilder().shape(UiSurfaceShape.ROUNDED_RECT).cornerRadius(14).build()));
        UiExtendedSurfacePanel hotbar=UiWidgets.hotbarPanel(ui,"surface-style-lab/hotbar",7,4,1);
        hotbar.layout().position(ui.sx(282),shapeY+ui.sx(16)).absolute(true); root.child(hotbar);

        float debugY=shapeY+ui.sx(92);
        root.child(card("surface-style-lab/distance-debug",12,debugY,ui.sx(120),ui.sx(48),base.toBuilder().debug(UiSurfaceDebugMode.SIGNED_DISTANCE).build()));
        root.child(card("surface-style-lab/noise-local",ui.sx(146),debugY,ui.sx(110),ui.sx(48),base.toBuilder().noise(UiSurfaceNoiseMode.SHARED_TEXTURE,.03f,UiSurfaceNoiseAnchor.ELEMENT_LOCAL).build()));
        root.child(card("surface-style-lab/noise-screen",ui.sx(270),debugY,ui.sx(110),ui.sx(48),base.toBuilder().noise(UiSurfaceNoiseMode.PROCEDURAL,.03f,UiSurfaceNoiseAnchor.SCREEN_SPACE).build()));
        UiGroup clipped=new UiGroup("surface-style-lab/clipped"); clipped.layout().position(ui.sx(394),debugY).size(ui.sx(130),ui.sx(48)).absolute(true).clip(true).flow(UiLayout.Flow.NONE);
        UiExtendedSurfacePanel clippedPanel=UiWidgets.extendedPanel(ui,"surface-style-lab/clipped/panel",ui.sx(150),ui.sx(44),ui.sx(82),ui.sx(24),ui.sx(6));
        clippedPanel.layout().position(ui.sx(-12),ui.sx(13)).absolute(true); clipped.child(clippedPanel); root.child(clipped);
        UiSurface moving=card("surface-style-lab/animated",ui.sx(550),debugY,ui.sx(90),ui.sx(42),base);
        moving.transform(UiTransform.translated((float)Math.sin(timeSeconds)*ui.sx(22),0));root.child(moving);
        float responsiveWidth=Math.max(ui.sx(80),Math.min(ui.sx(170),ui.width()*.12f));
        root.child(card("surface-style-lab/resize",ui.sx(674),debugY,responsiveWidth,ui.sx(42),base));

        UiGroup stress=new UiGroup("surface-style-lab/stress");
        stress.layout().position(12,debugY+ui.sx(68)).size(Math.min(w-ui.sx(24),ui.sx(790)),ui.sx(120)).absolute(true).clip(true).flow(UiLayout.Flow.NONE);
        UiSurfaceStyle stressStyle=base.toBuilder().gradient(0,1,.015f).noise(UiSurfaceNoiseMode.SHARED_TEXTURE,.006f,UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                .cornerRadius(2).borderWidth(.5f).shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build();
        for(int i=0;i<STRESS_SURFACES;i++){
            UiSurface surface=new UiSurface("surface-style-lab/stress/"+i,stressStyle);
            surface.layout().position((i%50)*ui.sx(15),(i/50)*ui.sx(11)).size(ui.sx(12),ui.sx(8)).absolute(true); stress.child(surface);
        }
        root.child(stress); return root;
    }

    private static UiSurface card(String id,float x,float y,float w,float h,UiSurfaceStyle style){UiSurface s=new UiSurface(id,style);s.layout().position(x,y).size(w,h).absolute(true);return s;}
    private static UiText label(String id,String text,float[] color,float x,float y){UiText t=new UiText(id,text,color);t.layout().position(x,y).absolute(true);return t;}
}
