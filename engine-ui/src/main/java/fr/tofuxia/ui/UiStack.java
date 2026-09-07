package fr.tofuxia.ui;

public final class UiStack extends UiGroup {
    public UiStack(String id) { super(id); layout().flow(UiLayout.Flow.STACK); }
}
