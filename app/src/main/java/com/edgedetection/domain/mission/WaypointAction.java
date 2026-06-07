package com.edgedetection.domain.mission;

/**
 * Sealed-like иерархия. Закрытый конструктор — нельзя наследовать снаружи пакета.
 */
public abstract class WaypointAction {

    private WaypointAction() {}

    public static final class Hover extends WaypointAction {
        public Hover() {}
    }

    public static final class TakePhoto extends WaypointAction {
        public TakePhoto() {}
    }

    public static final class StartVideo extends WaypointAction {
        public StartVideo() {}
    }

    public static final class StopVideo extends WaypointAction {
        public StopVideo() {}
    }

    public static final class RotateGimbal extends WaypointAction {
        public final float pitchDegrees;
        public RotateGimbal(float pitchDegrees) {
            this.pitchDegrees = pitchDegrees;
        }
    }
}