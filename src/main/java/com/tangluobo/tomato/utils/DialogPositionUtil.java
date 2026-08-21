package com.tangluobo.tomato.utils;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 多屏环境下将弹窗（Alert/Dialog/Stage）定位到主窗口所在屏幕的中心，
 * 避免默认行为将其弹到主屏（Screen.getPrimary）。
 */
public final class DialogPositionUtil {
    private DialogPositionUtil() {
    }

    /**
     * 按窗口中心点定位窗口当前所在屏幕。
     */
    public static Screen getScreenForWindow(Window window) {
        if (window == null) {
            return Screen.getPrimary();
        }
        double cx = window.getX() + window.getWidth() / 2.0;
        double cy = window.getY() + window.getHeight() / 2.0;
        for (Screen s : Screen.getScreens()) {
            if (s.getBounds().contains(cx, cy)) {
                return s;
            }
        }
        return Screen.getPrimary();
    }

    // ---------------- Dialog ----------------

    /** 将 Dialog 居中到 ownerNode 所在屏幕的中心。 */
    public static void centerOnOwner(Dialog<?> dlg, Node ownerNode) {
        if (dlg == null) {
            return;
        }
        if (ownerNode == null || ownerNode.getScene() == null || ownerNode.getScene().getWindow() == null) {
            centerOnOwner(dlg, (Window) null);
            return;
        }
        centerOnOwner(dlg, ownerNode.getScene().getWindow());
    }

    /**
     * 将 Dialog 居中到 owner 所在屏幕的中心。
     * 通过 initOwner 让 Dialog 默认先出现在 owner 附近（与 owner 同屏，不闪），
     * 再在 onShown 时把坐标修正为该屏幕 visualBounds 的中心。
     */
    public static void centerOnOwner(Dialog<?> dlg, Window owner) {
        if (dlg == null) {
            return;
        }
        if (owner != null) {
            try {
                dlg.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = (owner != null) ? owner : getMainWindow();
        dlg.setOnShown(e -> {
            Screen screen = getScreenForWindow(ownerRef);
            Rectangle2D b = screen.getVisualBounds();
            dlg.setX(b.getMinX() + (b.getWidth() - dlg.getWidth()) / 2.0);
            dlg.setY(b.getMinY() + (b.getHeight() - dlg.getHeight()) / 2.0);
        });
    }

    // ---------------- Stage ----------------

    /** 将 Stage 居中到 ownerNode 所在屏幕的中心。 */
    public static void centerOnOwner(Stage stage, Node ownerNode) {
        if (stage == null) {
            return;
        }
        if (ownerNode == null || ownerNode.getScene() == null || ownerNode.getScene().getWindow() == null) {
            centerOnOwner(stage, (Window) null);
            return;
        }
        centerOnOwner(stage, ownerNode.getScene().getWindow());
    }

    /** 将 Stage 居中到 owner 所在屏幕的中心。 */
    public static void centerOnOwner(Stage stage, Window owner) {
        if (stage == null) {
            return;
        }
        if (owner != null) {
            try {
                stage.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = (owner != null) ? owner : getMainWindow();
        stage.setOnShown(e -> {
            Screen screen = getScreenForWindow(ownerRef);
            Rectangle2D b = screen.getVisualBounds();
            stage.setX(b.getMinX() + (b.getWidth() - stage.getWidth()) / 2.0);
            stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2.0);
        });
    }

    /**
     * 在 show 之前直接计算并设置位置，避免 Stage 闪到默认位置再跳回。
     * 适用于 Stage 已通过 setScene + sizeToScene() 设置好尺寸的场景。
     */
    public static void preLocateOnOwner(Stage stage, Window owner) {
        if (stage == null) {
            return;
        }
        if (owner != null) {
            try {
                stage.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = (owner != null) ? owner : getMainWindow();
        Screen screen = getScreenForWindow(ownerRef);
        Rectangle2D b = screen.getVisualBounds();
        double w = stage.getWidth() > 0 ? stage.getWidth() : stage.getScene().getRoot().prefWidth(-1);
        double h = stage.getHeight() > 0 ? stage.getHeight() : stage.getScene().getRoot().prefHeight(-1);
        stage.setX(b.getMinX() + (b.getWidth() - w) / 2.0);
        stage.setY(b.getMinY() + (b.getHeight() - h) / 2.0);
    }

    /** 主窗口引用，由 TomatoApplication 在启动时设置。 */
    private static volatile Window mainWindow;

    public static void setMainWindow(Window window) {
        mainWindow = window;
    }

    public static Window getMainWindow() {
        return mainWindow;
    }
}
