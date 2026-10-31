import java.awt.*;
import java.awt.event.*;
import java.io.File;
import javax.imageio.ImageIO;

// Always launch with the owned private DISPLAY, never the user's desktop.
class RemoteDesktopInput {
    public static void main(String[] args) throws Exception {
        if (!"x11".equals(System.getenv("XDG_SESSION_TYPE")) || !System.getenv("DISPLAY").equals(System.getenv("KAINOS_REMOTE_PRIVATE_DISPLAY")))
            throw new IllegalStateException("Private display identity required");
        Robot robot = new Robot();
        switch (args[0]) {
            case "capture" -> ImageIO.write(robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png", new File(args[1]));
            case "click" -> {
                robot.mouseMove(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                int count = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                int gap = args.length > 4 ? Integer.parseInt(args[4]) : 100;
                for (int i = 0; i < count; i++) {
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    if (i + 1 < count) Thread.sleep(gap);
                }
            }
            case "space" -> { robot.keyPress(KeyEvent.VK_SPACE); robot.keyRelease(KeyEvent.VK_SPACE); }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
