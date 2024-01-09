package wrptn.scuffedcraft.models;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Getter
@Setter
public class SimulationInput {
    /**
     * Bloodlust, Heroism, Ancient Hysteria, Time Warp, Drums.
     */
    private boolean enableBloodlust = true;

    /**
     * Enables or disable scale factor calculations.
     */
    private boolean enableScaling = false;

    private int numberOfEnemies = 1;

    private FightStyle fightType = FightStyle.Patchwerk;

    private String profileString;

    private final String requestUUID = UUID.randomUUID().toString();

    private boolean isFormSubmit = false;

    private static String[] ALLOWED_TOKENS = new String[] {
        // Classes
        "priest", "warlock", "shaman", "druid", "warrior", "hunter", "mage", "monk", "demonhunter", "deathknight", "paladin", "rogue",
        // Item slots
        "head", "neck", "shoulder", "back", "chest", "wrist", "hands", "waist", "legs", "feet", "finger1", "finger2", "trinket1", "trinket2", "main_hand", "off_hand",
        // Common tokens
        "talents", "artifact", "position", "level", "role", "spec", "crucible"
    };

    /**
     * Returns a sanitized profile.
     */
    public Stream<String> getProfile() {
        return this.profileString.lines().filter(line -> {
            if (line.isEmpty())
                return false;

            if (line.charAt(0) == '#') // Strip comments
                return false;

            var tokens = line.split("=");
            if (tokens.length < 2)
                return false;

            if (Arrays.stream(ALLOWED_TOKENS).noneMatch(token -> Objects.equals(token, tokens[0]))) {
                switch (tokens[0]) {
                    case "race" -> {
                        // Explicitely block allied races, they are not implemented.
                        return switch (tokens[1]) {
                            case "nightborne", "lightforged_draenei", "highmountain_druid", "mechagnome" -> false;
                            default -> true;
                        };

                    }
                    default -> {
                        return false;
                    }
                }
            }

            return true;
        });
    }
}
