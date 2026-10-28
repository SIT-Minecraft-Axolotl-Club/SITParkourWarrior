package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete persisted description of one generated PKWS track. */
public final class PkwsTrack {
    private final String worldName;
    private final PkwsOrientation orientation;
    private final String generatedAt;
    private final List<Piece> pieces;

    public PkwsTrack(String worldName, PkwsOrientation orientation, String generatedAt,
                     List<Piece> pieces) {
        this.worldName = worldName;
        this.orientation = orientation;
        this.generatedAt = generatedAt;
        this.pieces = Collections.unmodifiableList(new ArrayList<>(pieces));
    }

    public String getWorldName() {
        return worldName;
    }

    public PkwsOrientation getOrientation() {
        return orientation;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public List<Piece> getPieces() {
        return pieces;
    }

    public enum PieceType {
        START,
        LEVEL,
        RELAY,
        END
    }

    /** One placed block in track order. */
    public static final class Piece {
        private final PieceType type;
        private final int segment;
        private final int position;
        private final String sourceId;
        private final String sourceTitle;
        private final Integer difficulty;
        private final int weight;
        private final Region region;
        private final PointLocation start;
        private final PointLocation end;
        /** Runtime Deployment id registered for this generated piece. */
        private final String deploymentId;
        private String gateState;

        public Piece(PieceType type, int segment, int position, String sourceId,
                     String sourceTitle, Integer difficulty, int weight,
                     Region region, PointLocation start, PointLocation end,
                     String gateState) {
            this(type, segment, position, sourceId, sourceTitle, difficulty, weight,
                    region, start, end, gateState, null);
        }

        public Piece(PieceType type, int segment, int position, String sourceId,
                     String sourceTitle, Integer difficulty, int weight,
                     Region region, PointLocation start, PointLocation end,
                     String gateState, String deploymentId) {
            this.type = type;
            this.segment = segment;
            this.position = position;
            this.sourceId = sourceId;
            this.sourceTitle = sourceTitle;
            this.difficulty = difficulty;
            this.weight = weight;
            this.region = region;
            this.start = start;
            this.end = end;
            this.deploymentId = deploymentId;
            this.gateState = gateState;
        }

        public PieceType getType() {
            return type;
        }

        public int getSegment() {
            return segment;
        }

        public int getPosition() {
            return position;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceTitle() {
            return sourceTitle;
        }

        public Integer getDifficulty() {
            return difficulty;
        }

        public int getWeight() {
            return weight;
        }

        public Region getRegion() {
            return region;
        }

        public PointLocation getStart() {
            return start;
        }

        public PointLocation getEnd() {
            return end;
        }

        public String getDeploymentId() {
            return deploymentId;
        }

        public String getGateState() {
            return gateState;
        }

        public void setGateState(String gateState) {
            this.gateState = gateState;
        }
    }
}
