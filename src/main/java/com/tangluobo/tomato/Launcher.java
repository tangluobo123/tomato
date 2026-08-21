package com.tangluobo.tomato;

import javafx.application.Application;
import java.io.OutputStream;
import java.io.PrintStream;

public class Launcher {
    public static void main(String[] args) {
        // 关闭 HiDPI 自动缩放：让 1 逻辑像素 = 1 物理像素，避免 Windows 显示缩放（如 150%）把界面整体放大
        System.setProperty("prism.allowhidpi", "false");
        // 启用LCD文字渲染，消除模糊
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "lcd");
        // javaw / jpackage GUI 启动器无控制台，System.out/err 为无效句柄，
        // JavaFX 内部（java.util.logging）写入会抛异常导致启动失败，重定向到空输出流规避
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), true));
        System.setErr(new PrintStream(OutputStream.nullOutputStream(), true));
        Application.launch(TomatoApplication.class, args);
    }
}
