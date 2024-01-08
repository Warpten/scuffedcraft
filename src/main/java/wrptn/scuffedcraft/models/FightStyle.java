package wrptn.scuffedcraft.models;

import lombok.Getter;

@Getter
public enum FightStyle {
    Patchwerk("Patchwerk"),
    HecticAddCleave("HecticAddCleave"),
    HelterSkelter("HelterSkelter"),
    Ultraxion("Ultraxion"),
    LightMovement("LightMovement"),
    HeavyMovement("HeavyMovement"),
    BeastLord("BeastLord"),
    CastingPatchwerk("CastingPatchwerk");

    private final String displayName;

    FightStyle(String displayName) {
        this.displayName = displayName;
    }
}
