package vibemod.parkourcheckpoints;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CourseManager {

    public static final class BlockMatch {
        public final Course course;
        public final int type;
        public final int checkpointIndex;

        public BlockMatch(Course course, int type, int checkpointIndex) {
            this.course = course;
            this.type = type;
            this.checkpointIndex = checkpointIndex;
        }
    }

    private final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<String, BlockMatch> index = new LinkedHashMap<>();

    public Course createCourse(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String key = name.toLowerCase();
        if (courses.containsKey(key)) {
            return null;
        }
        Course course = new Course(name);
        courses.put(key, course);
        return course;
    }

    public boolean deleteCourse(String name) {
        if (name == null) {
            return false;
        }
        Course removed = courses.remove(name.toLowerCase());
        if (removed != null) {
            rebuildIndex();
            return true;
        }
        return false;
    }

    public Course getCourse(String name) {
        if (name == null) {
            return null;
        }
        return courses.get(name.toLowerCase());
    }

    public List<Course> listCourses() {
        return new ArrayList<>(courses.values());
    }

    public void setStart(Course course, Location loc) {
        if (course == null) {
            return;
        }
        course.start = loc;
        rebuildIndex();
    }

    public void setEnd(Course course, Location loc) {
        if (course == null) {
            return;
        }
        course.end = loc;
        rebuildIndex();
    }

    public void addCheckpoint(Course course, Location loc) {
        if (course == null || loc == null) {
            return;
        }
        course.checkpoints.add(loc);
        rebuildIndex();
    }

    public void clearCheckpoints(Course course) {
        if (course == null) {
            return;
        }
        course.checkpoints.clear();
        rebuildIndex();
    }

    public BlockMatch findMatch(Location blockLoc) {
        if (blockLoc == null) {
            return null;
        }
        return index.get(key(blockLoc));
    }

    private void rebuildIndex() {
        index.clear();
        for (Course course : courses.values()) {
            if (course.start != null) {
                index.put(key(course.start), new BlockMatch(course, 0, -1));
            }
            if (course.end != null) {
                index.put(key(course.end), new BlockMatch(course, 2, -1));
            }
            for (int i = 0; i < course.checkpoints.size(); i++) {
                index.put(key(course.checkpoints.get(i)), new BlockMatch(course, 1, i));
            }
        }
    }

    private String key(Location loc) {
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "null";
        return worldName + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
