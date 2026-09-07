package fr.tofuxia.ui;

import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Reusable shader-driven surface whose text/icons remain ordinary children. */
public class UiSurface extends UiElement {
    private UiSurfaceStyle style;
    private UiSurfaceVariant variant=UiSurfaceVariant.NORMAL;
    private final EnumSet<UiVisualState> presentation=EnumSet.noneOf(UiVisualState.class);
    private final Map<UiVisualState,UiSurfaceStyle> stateStyles=new EnumMap<>(UiVisualState.class);

    public UiSurface(String id, UiSurfaceStyle style) { super(id); this.style=style; }
    public UiSurfaceStyle style(){return style;}
    public UiSurface style(UiSurfaceStyle value){style=value;return this;}
    public UiSurfaceVariant variant(){return variant;}
    public UiSurface variant(UiSurfaceVariant value){variant=value==null?UiSurfaceVariant.NORMAL:value;return this;}
    public UiSurface presentation(UiVisualState... states){presentation.clear();if(states!=null)for(UiVisualState state:states)if(state!=null)presentation.add(state);return this;}
    public UiSurface stateStyle(UiVisualState state,UiSurfaceStyle value){if(state!=null){if(value==null)stateStyles.remove(state);else stateStyles.put(state,value);}return this;}
    Set<UiVisualState> presentation(){return Set.copyOf(presentation);}
    UiSurfaceStyle resolveStyle(Set<UiVisualState> states,UiTheme theme){
        for(UiVisualState state:new UiVisualState[]{UiVisualState.DISABLED,UiVisualState.PRESSED,UiVisualState.HOVERED,UiVisualState.FOCUSED,UiVisualState.SELECTED}){
            UiSurfaceStyle override=stateStyles.get(state);if(states.contains(state)&&override!=null)return override;
        }
        return style.resolve(states,variant,theme);
    }
}
