import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class TestMain {
    static int starts;
    static int ends;

    public static final class Listener {
        @SubscribeEvent
        public void onStart(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                starts++;
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onEnd(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ends++;
            }
        }

        @SubscribeEvent
        public void onMouse(MouseEvent event) {
            event.setCanceled(true);
        }
    }

    public static void main(String[] args) {
        Listener listener = new Listener();
        MinecraftForge.EVENT_BUS.register(listener);

        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));

        if (starts != 1 || ends != 1) {
            throw new AssertionError("tick dispatch failed: starts=" + starts + " ends=" + ends);
        }

        MouseEvent mouse = new MouseEvent();
        mouse.button = 1;
        mouse.buttonstate = true;
        boolean canceled = MinecraftForge.EVENT_BUS.post(mouse);
        if (!canceled || !mouse.isCanceled()) {
            throw new AssertionError("cancelable mouse event did not cancel");
        }

        MinecraftForge.EVENT_BUS.unregister(listener);
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        if (starts != 1) {
            throw new AssertionError("unregister did not remove handlers: starts=" + starts);
        }

        System.out.println("EVENTBUS-OK");
    }
}
