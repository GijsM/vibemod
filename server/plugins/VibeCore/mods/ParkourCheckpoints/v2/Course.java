package vibemod.parkourcheckpoints;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public final class Course {
    public final String name;
    public Location start;
    public Location end;
    public final List<Location> checkpoints = new ArrayList<>();

    public Course(String name) {
        this.name = name;
    }
}
