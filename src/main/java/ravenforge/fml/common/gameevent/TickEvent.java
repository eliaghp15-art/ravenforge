package ravenforge.fml.common.gameevent;

import ravenforge.fml.common.eventhandler.Event;

public abstract class TickEvent extends Event {
    public enum Type { WORLD, PLAYER, CLIENT, SERVER, RENDER }

    public enum Phase { START, END }

    public final Type type;
    public final Phase phase;

    protected TickEvent(Type type, Phase phase) {
        this.type = type;
        this.phase = phase;
    }

    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) {
            super(Type.CLIENT, phase);
        }
    }

    public static class RenderTickEvent extends TickEvent {
        public final float renderTickTime;

        public RenderTickEvent(Phase phase, float renderTickTime) {
            super(Type.RENDER, phase);
            this.renderTickTime = renderTickTime;
        }
    }

    public static class ServerTickEvent extends TickEvent {
        public ServerTickEvent(Phase phase) {
            super(Type.SERVER, phase);
        }
    }

    public static class WorldTickEvent extends TickEvent {
        public WorldTickEvent(Phase phase) {
            super(Type.WORLD, phase);
        }
    }

    public static class PlayerTickEvent extends TickEvent {
        public PlayerTickEvent(Phase phase) {
            super(Type.PLAYER, phase);
        }
    }
}
