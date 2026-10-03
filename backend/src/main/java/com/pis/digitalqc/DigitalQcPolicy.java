package com.pis.digitalqc;
import static com.pis.digitalqc.DigitalQcContracts.*;
/** Synthetic contract checklist; no pixel analysis or clinical accuracy claim. */
public final class DigitalQcPolicy {
    private DigitalQcPolicy() {}
    public static boolean passed(Evaluation e) {
        return "PASS".equals(e.coverage()) && "PASS".equals(e.focus()) && "PASS".equals(e.missing())
            && Integer.valueOf(100).equals(e.coveragePercent()) && Integer.valueOf(0).equals(e.missingTiles())
            && e.regions()!=null && e.regions().isEmpty();
    }
    public static boolean regionsFit(Evaluation e,int width,int height) {
        return e.regions()!=null && e.regions().stream().allMatch(r -> r!=null && r.x()>=0 && r.y()>=0
            && r.width()>0 && r.height()>0 && (long)r.x()+r.width()<=width && (long)r.y()+r.height()<=height);
    }
}
