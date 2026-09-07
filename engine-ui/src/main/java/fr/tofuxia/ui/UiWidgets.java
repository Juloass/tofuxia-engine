package fr.tofuxia.ui;

import io.github.juloass.localization.TextComponent;
import java.util.function.Consumer;

/** Reusable widgets built entirely from declarative UI elements. */
public final class UiWidgets {
    private UiWidgets() {}

    public static UiSurface button(UiContext ui, String id, String label, float width, float height, Runnable click) {
        return button(ui, id, TextComponent.literal(label), width, height, click);
    }

    public static UiSurface button(UiContext ui, String id, TextComponent label, float width, float height, Runnable click) {
        UiSurface surface = new UiSurface(id, UiSurfaceStyle.button(ui.theme()));
        surface.layout().size(width, height).flow(UiLayout.Flow.STACK);
        surface.onEvent(event -> {
            if (event.type() == UiEvent.Type.CLICK && click != null) click.run();
        }).semantics(UiRole.BUTTON, label);
        UiText text = new UiText(id + "/label", label, ui.theme().text);
        text.layout().size(width, height);
        surface.child(text);
        return surface;
    }

    public static UiSurface dangerButton(UiContext ui,String id,String label,float width,float height,Runnable click){
        UiSurface button=button(ui,id,label,width,height,click); button.variant(UiSurfaceVariant.DANGER); return button;
    }

    public static UiSurface dangerButton(UiContext ui,String id,TextComponent label,float width,float height,Runnable click){
        UiSurface button=button(ui,id,label,width,height,click); button.variant(UiSurfaceVariant.DANGER); return button;
    }

    public static UiSurface surfacePanel(UiContext ui,String id,float width,float height){
        UiSurface panel=new UiSurface(id,UiSurfaceStyle.panel(ui.theme()));
        panel.layout().size(width,height).flow(UiLayout.Flow.STACK); return panel;
    }

    public static UiExtendedSurfacePanel extendedPanel(UiContext ui,String id,float width,float height,
                                                        float extensionWidth,float extensionHeight,float overlap){
        return new UiExtendedSurfacePanel(id,width,height,extensionWidth,extensionHeight,0,overlap,
                UiSurfaceStyle.panel(ui.theme()));
    }

    public static UiPageIndicator pageIndicator(UiContext ui,String id,int count,int active,float dotSize,float spacing){
        return new UiPageIndicator(id,count,active,dotSize,spacing,UiTheme.mix(ui.theme().charcoal,ui.theme().ivory,.32f),ui.theme().orange);
    }

    /** Demonstration hotbar: one union surface, normal slot children, and page dots in its extension. */
    public static UiExtendedSurfacePanel hotbarPanel(UiContext ui,String id,int slots,int pages,int activePage){
        float slot=ui.sx(38), gap=ui.sx(6), padding=ui.sx(10);
        int count=Math.max(1,slots); float width=padding*2+count*slot+(count-1)*gap, height=slot+padding*2;
        float extensionWidth=Math.max(ui.sx(70),UiPageIndicator.totalWidth(Math.max(1,pages),ui.sx(6),ui.sx(6))+ui.sx(20));
        UiExtendedSurfacePanel panel=extendedPanel(ui,id,width,height,extensionWidth,ui.sx(24),ui.sx(7));
        panel.body().layout().padding(padding).flow(UiLayout.Flow.ROW).gap(gap);
        for(int i=0;i<count;i++){
            UiSurface action=new UiSurface(id+"/slot-"+i,UiSurfaceStyle.button(ui.theme()).toBuilder()
                    .cornerRadius(5).shadow(UiTheme.rgba(0,0,0,0),0,0,0,0).build());
            action.layout().size(slot,slot); panel.bodyChild(action);
        }
        UiPageIndicator dots=pageIndicator(ui,id+"/pages",Math.max(1,pages),activePage,ui.sx(6),ui.sx(6));
        dots.layout().position((extensionWidth-dots.layout().width)*.5f,ui.sx(7)).absolute(true);
        panel.extensionChild(dots); return panel;
    }

    public static UiBox checkbox(UiContext ui, String id, String label, boolean checked,
                                 Consumer<Boolean> changed) {
        return checkbox(ui, id, TextComponent.literal(label), checked, changed);
    }

    public static UiBox checkbox(UiContext ui, String id, TextComponent label, boolean checked,
                                 Consumer<Boolean> changed) {
        UiBox row = new UiBox(id, ui.theme().panel).border(ui.theme().borderDim, 1);
        row.layout().size(ui.sx(180), ui.sx(24)).flow(UiLayout.Flow.ROW).gap(ui.sx(6))
                .align(UiLayout.Align.START, UiLayout.Align.CENTER);
        row.selected(checked).onEvent(event -> {
            if (event.type() == UiEvent.Type.CLICK && changed != null) changed.accept(!checked);
        }).semantics(UiRole.CHECKBOX, label).accessibleValue(Boolean.toString(checked));
        UiBox mark = new UiBox(id + "/mark", checked ? ui.theme().success : ui.theme().panelPressed)
                .border(checked ? ui.theme().success : ui.theme().border, 1);
        mark.layout().size(ui.sx(16), ui.sx(16));
        row.children(mark, new UiText(id + "/label", label, ui.theme().text));
        return row;
    }

    public static UiGroup progress(UiContext ui, String id, float width, float height, float value,
                                   float[] fill, String label) {
        return progress(ui, id, width, height, value, fill, TextComponent.literal(label));
    }

    public static UiGroup progress(UiContext ui, String id, float width, float height, float value,
                                   float[] fill, TextComponent label) {
        float progress = Math.max(0, Math.min(1, value));
        UiStack stack = new UiStack(id);
        stack.semantics(UiRole.PROGRESS_BAR, label).accessibleValue(Float.toString(progress));
        stack.layout().size(width, height);
        UiBox track = new UiBox(id + "/track", ui.theme().panelPressed).border(ui.theme().border, 1);
        track.layout().size(width, height);
        UiBox bar = new UiBox(id + "/fill", fill);
        bar.layout().size(width * progress, height);
        stack.children(track, bar, new UiText(id + "/label", label, ui.theme().text));
        return stack;
    }

    public static UiBox slider(UiContext ui, String id, float width, float value, float min, float max,
                               Consumer<Float> changed) {
        float normalized = Math.max(0, Math.min(1, (value-min) / Math.max(.000001f, max-min)));
        UiBox track = new UiBox(id, ui.theme().panelPressed).border(ui.theme().borderDim, 1);
        track.layout().size(width, ui.sx(14)).flow(UiLayout.Flow.STACK);
        track.onEvent(event -> {
            if ((event.type() == UiEvent.Type.PRESS || event.type() == UiEvent.Type.DRAG) && changed != null) {
                changed.accept(min + Math.max(0, Math.min(1, event.localX()/width)) * (max-min));
            }
        }).semantics(UiRole.SLIDER, id).accessibleValue(Float.toString(value));
        UiBox fill = new UiBox(id + "/fill", ui.theme().accentBlue);
        fill.layout().size(width * normalized, ui.sx(14));
        track.child(fill);
        return track;
    }

    public static UiBox textField(UiContext ui, String id, float width, StringBuilder value, String placeholder) {
        return textField(ui, id, width, value, TextComponent.literal(placeholder));
    }

    public static UiBox textField(UiContext ui, String id, float width, StringBuilder value, TextComponent placeholder) {
        UiBox field = new UiBox(id, ui.theme().panel).border(ui.theme().border, 1);
        field.layout().size(width, ui.sx(26)).flow(UiLayout.Flow.STACK).padding(ui.sx(5));
        field.onEvent(event -> {
            if (event.type() == UiEvent.Type.TEXT_INPUT) value.append(event.text());
            if (event.type() == UiEvent.Type.EDIT && event.editKey() == UiEvent.EditKey.BACKSPACE && !value.isEmpty()) {
                value.deleteCharAt(value.length()-1);
            }
            if (event.type() == UiEvent.Type.EDIT && event.editKey() == UiEvent.EditKey.DELETE) value.setLength(0);
        }).semantics(UiRole.TEXT_FIELD, placeholder).accessibleValue(value.toString());
        field.child(new UiText(id + "/text", value.isEmpty() ? placeholder : TextComponent.literal(value.toString()),
                value.isEmpty() ? ui.theme().textDim : ui.theme().text));
        return field;
    }
}
