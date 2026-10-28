package club.sitmc.sitParkourWarrior.map;

public enum NodeType {
    LEVEL,
    FORK,
    GLOBAL_START,
    GLOBAL_END,
    BRANCH_END,
    PKWS_RELAY,
    PKWS_START,
    PKWS_END;

    public static NodeType fromString(String value) {
        if (value == null) {
            return LEVEL;
        }
        switch (value.trim().toLowerCase()) {
            case "level":       return LEVEL;
            case "fork":        return FORK;
            case "globalstart": return GLOBAL_START;
            case "globalend":   return GLOBAL_END;
            case "branchend":   return BRANCH_END;
            case "pkws_relay":
            case "pkwsrelay":
            case "relay":       return PKWS_RELAY;
            case "pkws_start":
            case "pkwsstart":
            case "pkwsbegin":
            case "start":       return PKWS_START;
            case "pkws_end":
            case "pkwsend":
            case "end":         return PKWS_END;
            default:            return LEVEL;
        }
    }

    /**
     * Parses only the PKWS-specific type names.  This keeps a PKWS command
     * from accidentally accepting one of the legacy PKW course node types.
     */
    public static NodeType fromPkwsString(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase()) {
            case "pkws_relay":
            case "pkwsrelay":
            case "relay":
                return PKWS_RELAY;
            case "pkws_start":
            case "pkwsstart":
            case "pkwsbegin":
            case "start":
                return PKWS_START;
            case "pkws_end":
            case "pkwsend":
            case "end":
                return PKWS_END;
            default:
                return null;
        }
    }

    /**
     * PKWS nodes are deliberately not part of the legacy PKW course model.
     */
    public boolean isPkwsType() {
        return this == PKWS_RELAY || this == PKWS_START || this == PKWS_END;
    }

    /** Returns the canonical config string used by map.yml. */
    public String toConfigString() {
        switch (this) {
            case LEVEL:        return "level";
            case FORK:         return "fork";
            case GLOBAL_START: return "globalstart";
            case GLOBAL_END:   return "globalend";
            case BRANCH_END:   return "branchend";
            case PKWS_RELAY:   return "pkws_relay";
            case PKWS_START:   return "pkws_start";
            case PKWS_END:     return "pkws_end";
            default:           return "level";
        }
    }
}
