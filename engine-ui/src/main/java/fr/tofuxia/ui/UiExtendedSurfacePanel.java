package fr.tofuxia.ui;

/** One union silhouette with independently populated body and extension areas. */
public final class UiExtendedSurfacePanel extends UiSurface {
    private final UiGroup body;
    private final UiGroup extension;

    public UiExtendedSurfacePanel(String id, float width, float height, float extensionWidth,
                                  float extensionHeight, float extensionOffset, float overlap,
                                  UiSurfaceStyle baseStyle) {
        super(id, baseStyle.toBuilder().shape(UiSurfaceShape.TOP_CENTER_EXTENSION_PANEL)
                .extension(extensionWidth,extensionHeight,extensionOffset,overlap,
                        Math.min(baseStyle.cornerRadius(),extensionHeight*.5f)).build());
        layout().size(width,height).flow(UiLayout.Flow.STACK);
        body=new UiGroup(id+"/body"); body.layout().size(width,height).flow(UiLayout.Flow.STACK);
        extension=new UiGroup(id+"/extension");
        extension.layout().position((width-extensionWidth)*.5f+extensionOffset,-extensionHeight+overlap)
                .size(extensionWidth,extensionHeight).absolute(true).flow(UiLayout.Flow.STACK);
        children(body,extension);
    }

    public UiGroup body(){return body;}
    public UiGroup extension(){return extension;}
    public UiExtendedSurfacePanel bodyChild(UiElement child){body.child(child);return this;}
    public UiExtendedSurfacePanel extensionChild(UiElement child){extension.child(child);return this;}
}
