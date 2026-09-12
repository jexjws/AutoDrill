package autodrill.filler;

import arc.math.geom.Point2;

public enum Direction {
    RIGHT(new Point2(1, 0), 0),
    UP(new Point2(0, 1), 1),
    LEFT(new Point2(-1, 0), 2),
    DOWN(new Point2(0, -1), 3);

    public final Point2 p;
    public final int r;

    Direction(Point2 p, int r) {
        this.p = p;
        this.r = r;
    }

    public int primaryAxis(Point2 p) {
        return p.x * this.p.x + p.y * this.p.y;
    }

    public int secondaryAxis(Point2 p) {
        return p.x * this.p.y + p.y * this.p.x;
    }

    public int primaryAxis(int x, int y) {
        return x * this.p.x + y * this.p.y;
    }

    public int secondaryAxis(int x, int y) {
        return x * this.p.y + y * this.p.x;
    }

    public Point2 toWorld(int pa, int sa) {
        switch (this) {
            case RIGHT: return new Point2(pa, sa);
            case UP:    return new Point2(sa, pa);
            case LEFT:  return new Point2(-pa, -sa);
            default:    return new Point2(-sa, -pa); // DOWN
        }
    }

    public Point2 anchorFromAxes(int backPa, int saMin, int size) {
        int off = -((size - 1) / 2);
        int xMin, yMin;
        switch (this) {
            case RIGHT:
                xMin = backPa;
                yMin = saMin;
                break;
            case UP:
                xMin = saMin;
                yMin = backPa;
                break;
            case LEFT:
                xMin = -backPa - (size - 1);
                yMin = -saMin - (size - 1);
                break;
            default: // DOWN
                xMin = -saMin - (size - 1);
                yMin = -backPa - (size - 1);
                break;
        }
        return new Point2(xMin - off, yMin - off);
    }

    public int getBackPa(int x, int y, int size) {
        int off = -((size - 1) / 2);
        int xMin = x + off;
        int yMin = y + off;
        switch (this) {
            case RIGHT: return xMin;
            case UP:    return yMin;
            case LEFT:  return -xMin - (size - 1);
            default:    return -yMin - (size - 1); // DOWN
        }
    }

    public int getSaMin(int x, int y, int size) {
        int off = -((size - 1) / 2);
        int xMin = x + off;
        int yMin = y + off;
        switch (this) {
            case RIGHT: return yMin;
            case UP:    return xMin;
            case LEFT:  return -yMin - (size - 1);
            default:    return -xMin - (size - 1); // DOWN
        }
    }

    public static Direction getOpposite(Direction direction) {
        switch (direction) {
            case RIGHT -> {
                return LEFT;
            }
            case UP -> {
                return DOWN;
            }
            case LEFT -> {
                return RIGHT;
            }
            default -> {
                return UP;
            }
        }
    }
}
